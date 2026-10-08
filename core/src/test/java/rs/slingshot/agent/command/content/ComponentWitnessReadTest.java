// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import rs.slingshot.agent.stream.ElapsedTime;

/** Qualified pages retain their first witnesses while every descendant remains currently checked. */
@ExtendWith(SlingContextExtension.class)
final class ComponentWitnessReadTest {

    private static final String ROOT = "/content/synthetic-witness-read";
    private static final String TEXT = "synthetic/components/text";
    private static final String IMAGE = "synthetic/components/image";
    private static final int TAIL_NODES = 128;
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @ParameterizedTest
    @EnumSource(MatchMode.class)
    void qualifiedPagesSkipRedundantTypeReadsButStillDiscoverNestedPages(MatchMode mode) {
        final Resource root = createPage(ROOT);
        final Set<String> tail = tail();
        final String nested = ROOT + "/nested";
        createPage(nested);
        final var observed = new Reads();
        try (var current = observed.resolver(sling.resourceResolver(), tail);
                var walk = new ResumableWalk<String>(root, 8)) {
            assertEquals(List.of(nested, ROOT), enumerate(walk, current, mode));
        }
        assertEquals(TAIL_NODES, observed.current.get(), "every tail node must recheck current authority");
        assertEquals(TAIL_NODES, observed.primary.get(), "nested-page classification must remain live");
        assertEquals(0, observed.component.get(), "a qualified page already holds its first witnesses");
    }

    @ParameterizedTest
    @EnumSource(MatchMode.class)
    void unmatchedPagesContinueReadingTypesUntilTheRequiredWitnessesArrive(MatchMode mode) {
        final Resource root = sling.create().resource(ROOT, "jcr:primaryType", "cq:Page");
        sling.create().resource(ROOT + "/jcr:content", "jcr:primaryType", "cq:PageContent");
        final Set<String> tail = tail();
        sling.create().resource(ROOT + "/jcr:content/text", "sling:resourceType", TEXT);
        sling.create().resource(ROOT + "/jcr:content/image", "sling:resourceType", IMAGE);
        final var observed = new Reads();
        try (var current = observed.resolver(sling.resourceResolver(), tail);
                var walk = new ResumableWalk<String>(root, 8)) {
            assertEquals(List.of(ROOT), enumerate(walk, current, mode));
        }
        assertEquals(TAIL_NODES, observed.current.get());
        assertEquals(TAIL_NODES, observed.primary.get());
        assertEquals(TAIL_NODES, observed.component.get(), "an unmatched page cannot skip its search");
    }

    private Resource createPage(String path) {
        final Resource page = sling.create().resource(path, "jcr:primaryType", "cq:Page");
        sling.create().resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent");
        sling.create().resource(path + "/jcr:content/text", "sling:resourceType", TEXT);
        sling.create().resource(path + "/jcr:content/image", "sling:resourceType", IMAGE);
        return page;
    }

    private Set<String> tail() {
        return IntStream.range(0, TAIL_NODES).mapToObj(index -> ROOT + "/jcr:content/tail-" + index)
                .peek(path -> sling.create().resource(path, "jcr:primaryType", "nt:unstructured",
                        "sling:resourceType", "synthetic/components/unused"))
                .collect(Collectors.toSet());
    }

    private static List<String> enumerate(ResumableWalk<String> walk, ResourceResolver current,
                                          MatchMode mode) {
        final List<String> found = new ArrayList<>();
        boolean complete = false;
        int pages = 0;
        while (!complete) {
            final var outcome = walk.advance(new ResumableWalk.Limits(20, 1, 100), current,
                    resource -> true, resource -> Optional.of(resource.getPath()), () -> false,
                    ElapsedTime.start(() -> 0), new ResumableWalk.Grouping(List.of(TEXT, IMAGE), mode));
            final var result = switch (outcome) {
                case ResumableWalk.Page<String> page -> page;
                case ResumableWalk.Refused<String> refused -> throw new AssertionError(refused.reason());
            };
            assertTrue(result.examined() <= 20);
            assertTrue(result.rows().size() <= 1);
            found.addAll(result.rows());
            complete = result.complete();
            pages++;
            assertTrue(pages <= TAIL_NODES, "the same retained walk must finish within finite work");
        }
        return found;
    }

    private static final class Reads {
        private final AtomicLong current = new AtomicLong();
        private final AtomicLong primary = new AtomicLong();
        private final AtomicLong component = new AtomicLong();

        private ResourceResolver resolver(ResourceResolver delegate, Set<String> tail) {
            return new ResourceResolverWrapper(delegate) {
                @Override
                public Resource getResource(String path) {
                    final Resource resource = super.getResource(path);
                    if (!tail.contains(path)) {
                        return resource;
                    }
                    current.incrementAndGet();
                    return new ResourceWrapper(resource) {
                        @Override
                        public ValueMap getValueMap() {
                            return values(super.getValueMap());
                        }
                    };
                }
            };
        }

        private ValueMap values(ValueMap delegate) {
            return new ValueMapDecorator(delegate) {
                @Override
                public <Value> Value get(String name, Class<Value> type) {
                    if ("jcr:primaryType".equals(name)) {
                        primary.incrementAndGet();
                    }
                    return super.get(name, type);
                }

                @Override
                public <Value> Value get(String name, Value fallback) {
                    if (FindPagesUsingComponentsHandler.RESOURCE_TYPE_PROPERTY.equals(name)) {
                        component.incrementAndGet();
                    }
                    return super.get(name, fallback);
                }
            };
        }
    }
}
