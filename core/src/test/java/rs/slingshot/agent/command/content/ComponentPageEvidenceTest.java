// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.stream.ElapsedTime;

/** Witness validation observes cooperative boundaries and current repository authority. */
@ExtendWith(SlingContextExtension.class)
final class ComponentPageEvidenceTest {

    private static final String PAGE = "/content/synthetic-proof/page";
    private static final String CONTENT = PAGE + "/jcr:content";
    private static final String TEXT = "synthetic/components/text";
    private static final String IMAGE = "synthetic/components/image";
    private static final long MILLISECONDS = 10;
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    void commonAncestorsAreReadOnceWithinTheExactProofAllowance() {
        final var evidence = corpus();
        final AtomicLong reads = new AtomicLong();
        try (var current = observed(reads, () -> { })) {
            final var checked = evidence.validate(current, timer(), MILLISECONDS, () -> false);
            assertEquals(ComponentPageEvidence.State.ACCEPTED, checked.state());
            assertEquals(4, evidence.work());
            assertEquals(evidence.work(), checked.examined());
            assertEquals(evidence.work(), reads.get());
            assertEquals(List.of(PAGE), checked.pages().stream().map(Resource::getPath).toList());
        }
    }

    @Test
    void theTimeBoundaryStopsBeforeAnotherReadAndAnewRequestCanFinish() {
        final var evidence = corpus();
        final AtomicLong clock = new AtomicLong();
        final AtomicLong reads = new AtomicLong();
        try (var current = observed(reads, () -> clock.set(MILLISECONDS))) {
            final var checked = evidence.validate(current, ElapsedTime.start(clock::get),
                    MILLISECONDS, () -> false);
            assertEquals(ComponentPageEvidence.State.TIME_BUDGET, checked.state());
            assertEquals(1, checked.examined());
            assertEquals(1, reads.get());
            assertTrue(checked.pages().isEmpty());
            assertEquals(ComponentPageEvidence.State.ACCEPTED,
                    evidence.validate(sling.resourceResolver(), timer(), MILLISECONDS, () -> false).state());
        }
    }

    @Test
    void cancellationDuringAProviderReadStopsBeforeAnotherWitness() {
        final var evidence = corpus();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicLong reads = new AtomicLong();
        try (var current = observed(reads, () -> cancelled.set(true))) {
            final var checked = evidence.validate(current, timer(), MILLISECONDS, cancelled::get);
            assertEquals(ComponentPageEvidence.State.CANCELLED, checked.state());
            assertEquals(1, checked.examined());
            assertEquals(1, reads.get());
            assertTrue(checked.pages().isEmpty());
        }
    }

    @Test
    void anAlreadyCancelledOrExpiredRequestDoesNotReadAnyWitness() {
        final var evidence = corpus();
        final AtomicLong reads = new AtomicLong();
        final AtomicLong clock = new AtomicLong();
        final var elapsed = ElapsedTime.start(clock::get);
        clock.set(MILLISECONDS);
        try (var current = observed(reads, () -> { })) {
            assertEquals(ComponentPageEvidence.State.CANCELLED,
                    evidence.validate(current, timer(), MILLISECONDS, () -> true).state());
            assertEquals(ComponentPageEvidence.State.TIME_BUDGET,
                    evidence.validate(current, elapsed, MILLISECONDS, () -> false).state());
            assertEquals(0, reads.get());
        }
    }

    @Test
    void aChangedComponentTypeCannotJustifyAnAllTypesPage()
            throws org.apache.sling.api.resource.PersistenceException {
        final var evidence = corpus();
        sling.resourceResolver().delete(Optional.ofNullable(
                sling.resourceResolver().getResource(CONTENT + "/image")).orElseThrow());
        sling.create().resource(CONTENT + "/image", "sling:resourceType", TEXT);
        assertEquals(ComponentPageEvidence.State.CHANGED,
                evidence.validate(sling.resourceResolver(), timer(), MILLISECONDS, () -> false).state());
    }

    @Test
    void anUnreadableInterveningAncestorCannotJustifyAReadablePage() {
        final var evidence = corpus();
        try (ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
            @Override
            public Resource getResource(String path) {
                return CONTENT.equals(path) ? null : super.getResource(path);
            }
        }) {
            final var checked = evidence.validate(current, timer(), MILLISECONDS, () -> false);
            assertEquals(ComponentPageEvidence.State.CHANGED, checked.state());
            assertTrue(checked.pages().isEmpty());
        }
    }

    @Test
    void aProviderCannotReplaceARequiredWitnessWithAnotherReadableResource() {
        final var evidence = corpus();
        final Resource other = sling.create().resource("/content/synthetic-proof/other",
                "sling:resourceType", IMAGE);
        try (ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
            @Override
            public Resource getResource(String path) {
                return (CONTENT + "/image").equals(path) ? other : super.getResource(path);
            }
        }) {
            assertEquals(ComponentPageEvidence.State.CHANGED,
                    evidence.validate(current, timer(), MILLISECONDS, () -> false).state());
        }
    }

    @Test
    void proofAncestryAcceptsTheDepthBoundaryAndRefusesOneStepBeyond() {
        corpus();
        assertEquals(4, new ComponentPageEvidence(PAGE, sources(), 2).work());
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentPageEvidence(PAGE, sources(), 1));
    }

    private ComponentPageEvidence corpus() {
        sling.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        sling.create().resource(CONTENT, "jcr:primaryType", "cq:PageContent");
        sling.create().resource(CONTENT + "/text", "sling:resourceType", TEXT);
        sling.create().resource(CONTENT + "/image", "sling:resourceType", IMAGE);
        return new ComponentPageEvidence(PAGE, sources(), 2);
    }

    private static SequencedMap<String, String> sources() {
        return new LinkedHashMap<>(Map.of(TEXT, CONTENT + "/text", IMAGE, CONTENT + "/image"));
    }

    private ResourceResolver observed(AtomicLong reads, Runnable afterRead) {
        return new ResourceResolverWrapper(sling.resourceResolver()) {
            @Override
            public Resource getResource(String path) {
                reads.incrementAndGet();
                final Resource resource = super.getResource(path);
                afterRead.run();
                return resource;
            }
        };
    }

    private static ElapsedTime timer() {
        return ElapsedTime.start(() -> 0);
    }
}
