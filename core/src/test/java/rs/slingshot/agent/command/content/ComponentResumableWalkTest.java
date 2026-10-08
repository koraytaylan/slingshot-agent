// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.stream.ElapsedTime;

/** Page grouping cannot publish incomplete authority proofs or escape cursor bounds. */
@ExtendWith(SlingContextExtension.class)
final class ComponentResumableWalkTest {

    private static final String ROOT = "/content/synthetic-group";
    private static final String PAGE = ROOT + "/page";
    private static final String TEXT = "synthetic/components/text";
    private static final String IMAGE = "synthetic/components/image";
    private static final long MILLISECONDS = 10;
    private static final ResumableWalk.Limits LIMITS = new ResumableWalk.Limits(30, 1, MILLISECONDS);
    private static final ResumableWalk.Grouping GROUPING =
            new ResumableWalk.Grouping(List.of(TEXT, IMAGE), MatchMode.ALL);
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void aComponentVisitReadsItsCurrentPrimaryTypeOnce() {
        final Resource root = sling.create().resource(ROOT);
        final String child = ROOT + "/unmatched";
        sling.create().resource(child, "jcr:primaryType", "nt:unstructured");
        final AtomicLong primaryReads = new AtomicLong();
        final AtomicLong currentReads = new AtomicLong();
        final ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
            @Override
            public Resource getResource(String path) {
                final Resource resource = super.getResource(path);
                if (!child.equals(path)) {
                    return resource;
                }
                currentReads.incrementAndGet();
                return new ResourceWrapper(resource) {
                    @Override
                    public ValueMap getValueMap() {
                        return new ValueMapDecorator(super.getValueMap()) {
                            @Override
                            public <Value> Value get(String name, Class<Value> type) {
                                if ("jcr:primaryType".equals(name)) {
                                    primaryReads.incrementAndGet();
                                }
                                return super.get(name, type);
                            }
                        };
                    }
                };
            }
        };
        try (current; var walk = new ResumableWalk<String>(root, 8)) {
            final var result = page(advance(walk, current, () -> false, timer()));
            assertTrue(result.complete());
            assertTrue(result.rows().isEmpty());
            assertEquals(1, currentReads.get(), "the visit must still check current readability");
            assertEquals(1, primaryReads.get(), "classification must not adapt the same primary type twice");
        }
    }

    @Test
    void expiryDuringAProofPublishesNoRowAndTheNextPageFinishesTheSameGroup() {
        final Resource root = corpus();
        final AtomicLong clock = new AtomicLong();
        try (var walk = new ResumableWalk<String>(root, 8);
                var current = duringProof(() -> clock.set(MILLISECONDS))) {
            final var first = page(advance(walk, current, () -> false, ElapsedTime.start(clock::get)));
            assertEquals(ResumableWalk.Boundary.TIME_BUDGET, first.boundary());
            assertTrue(first.rows().isEmpty());
            assertTrue(first.evidence().isEmpty());
            assertFalse(first.complete());
            final var resumed = page(advance(walk, sling.resourceResolver(), () -> false, timer()));
            assertEquals(List.of(PAGE), resumed.rows());
            assertEquals(4, resumed.evidence().get(PAGE).work());
            assertTrue(page(advance(walk, sling.resourceResolver(), () -> false, timer())).complete());
        }
    }

    @Test
    void cancellationDuringAProofDiscardsTheGroupAndClosesItsCursor() {
        final Resource root = corpus();
        final AtomicBoolean cancelled = new AtomicBoolean();
        try (var walk = new ResumableWalk<String>(root, 8);
                var current = duringProof(() -> cancelled.set(true))) {
            assertEquals(ResumableWalk.Stop.CANCELLED,
                    refused(advance(walk, current, cancelled::get, timer())));
            assertEquals(ResumableWalk.Stop.CLOSED,
                    refused(advance(walk, sling.resourceResolver(), () -> false, timer())));
        }
    }

    @Test
    void nestedPageGroupsFinishInProviderOrderWithoutDuplicatesOrSharedWitnesses() {
        final Resource root = corpus();
        final String nested = PAGE + "/nested";
        createPage(nested);
        final List<String> found = new ArrayList<>();
        final var limits = new ResumableWalk.Limits(4, 1, MILLISECONDS);
        try (var walk = new ResumableWalk<String>(root, 8)) {
            boolean complete = false;
            int requests = 0;
            while (!complete) {
                final var result = page(walk.advance(limits, sling.resourceResolver(), resource -> true,
                        resource -> Optional.of(resource.getPath()), () -> false, timer(), GROUPING));
                assertTrue(result.examined() <= limits.nodes());
                assertTrue(result.rows().size() <= limits.matches());
                found.addAll(result.rows());
                complete = result.complete();
                requests++;
                assertTrue(requests <= 100, "a stable grouped tree must make finite progress");
            }
        }
        assertEquals(List.of(nested, PAGE), found);
    }

    @Test
    void findingAContainingPageAboveTheAnchorStillHonoursTheDepthBound() {
        corpus();
        final Resource anchor = sling.resourceResolver().getResource(PAGE + "/jcr:content/text");
        try (var walk = new ResumableWalk<String>(anchor, 1)) {
            assertEquals(ResumableWalk.Stop.DEPTH_EXCEEDED,
                    refused(advance(walk, sling.resourceResolver(), () -> false, timer())));
        }
        try (var walk = new ResumableWalk<String>(anchor, 2)) {
            final var any = new ResumableWalk.Grouping(List.of(TEXT), MatchMode.ANY);
            final var result = page(walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> Optional.of(resource.getPath()), () -> false, timer(), any));
            assertEquals(List.of(PAGE), result.rows());
        }
    }

    private Resource corpus() {
        final Resource root = sling.create().resource(ROOT);
        createPage(PAGE);
        return root;
    }

    private void createPage(String path) {
        sling.create().resource(path, "jcr:primaryType", "cq:Page");
        sling.create().resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent");
        sling.create().resource(path + "/jcr:content/text", "sling:resourceType", TEXT);
        sling.create().resource(path + "/jcr:content/image", "sling:resourceType", IMAGE);
    }

    private ResourceResolver duringProof(Runnable action) {
        final AtomicLong reads = new AtomicLong();
        return new ResourceResolverWrapper(sling.resourceResolver()) {
            @Override
            public Resource getResource(String path) {
                final Resource resource = super.getResource(path);
                if ((PAGE + "/jcr:content/text").equals(path) && reads.incrementAndGet() == 2) {
                    action.run();
                }
                return resource;
            }
        };
    }

    private static ResumableWalk.Outcome<String> advance(ResumableWalk<String> walk,
                                                       ResourceResolver current,
                                                       java.util.function.BooleanSupplier cancelled,
                                                       ElapsedTime elapsed) {
        return walk.advance(LIMITS, current, resource -> true,
                resource -> Optional.of(resource.getPath()), cancelled, elapsed, GROUPING);
    }

    private static ElapsedTime timer() {
        return ElapsedTime.start(() -> 0);
    }

    private static ResumableWalk.Page<String> page(ResumableWalk.Outcome<String> outcome) {
        assertInstanceOf(ResumableWalk.Page.class, outcome);
        return switch (outcome) {
            case ResumableWalk.Page<String> page -> page;
            case ResumableWalk.Refused<String> refused -> throw new AssertionError(refused.reason());
        };
    }

    private static ResumableWalk.Stop refused(ResumableWalk.Outcome<?> outcome) {
        return assertInstanceOf(ResumableWalk.Refused.class, outcome).reason();
    }
}
