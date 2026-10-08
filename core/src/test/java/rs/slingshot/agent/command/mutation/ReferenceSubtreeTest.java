// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

/** External reference visibility when the target's internal subtree is irrelevant. */
final class ReferenceSubtreeTest {

    private static final String TARGET = "/content/synthetic-target";

    @Test
    void ownWideSubtreeDoesNotConsumeTheExternalReferenceBudget() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource target = wideTarget(advances);
        final Resource external = resource("/content/synthetic-external", List.of(), advances,
                Map.of("link", TARGET));
        final Resource root = resource("/content", List.of(target, external), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 3);
            assertTrue(found.complete(), "irrelevant descendants exhausted the external budget");
            assertEquals(List.of(external), found.found());
        }
        assertEquals(2, advances.get(), "the scanner consumed the target's internal children");
    }

    @Test
    void fullDiscoveryPrunesDeepInternalReferencesAndFindsTheLastExternalSibling() {
        final AtomicInteger advances = new AtomicInteger();
        Resource nested = resource(TARGET + "/child".repeat(100), List.of(), advances,
                Map.of("link", TARGET));
        for (int depth = 99; depth > 0; depth--) {
            nested = resource(TARGET + "/child".repeat(depth), List.of(nested), advances,
                    Map.of("link", TARGET));
        }
        final Resource target = resource(TARGET, List.of(nested), advances, Map.of("link", TARGET));
        final Resource first = resource("/content/synthetic-first", List.of(), advances,
                Map.of("link", TARGET));
        final Resource last = resource("/content/synthetic-last", List.of(), advances,
                Map.of("links", new String[]{"/content/synthetic-unrelated", TARGET}));
        final Resource root = resource("/content", List.of(target, first, last), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 4);
            assertTrue(found.complete());
            assertEquals(List.of(first, last), found.found());
        }
        assertEquals(3, advances.get(), "the scanner followed the target's internal active path");
    }

    @Test
    void deletionAbsenceCompletesAfterExcludedSubtreeWithinTheSameBudget() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource root = resource("/content", List.of(wideTarget(advances)), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 2));
        }
        assertEquals(1, advances.get(), "proving external absence traversed internal references");
    }

    @Test
    void externalBranchBeyondTheBudgetStillRequiresConservativeRefusal() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource external = resource("/content/synthetic-external", List.of(), advances,
                Map.of("link", TARGET));
        final Resource root = resource("/content", List.of(wideTarget(advances), external),
                advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 0));
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 1));
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 2));
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 2);
            assertFalse(found.complete());
            assertTrue(found.found().isEmpty());
        }
    }

    @Test
    void unreadableExternalMetadataStillPreventsCompleteDiscovery() {
        final AtomicInteger advances = new AtomicInteger();
        final AtomicInteger metadataReads = new AtomicInteger();
        final Resource root = resource("/content", List.of(wideTarget(advances),
                unreadableExternal(metadataReads)), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 3);
            assertFalse(found.complete());
            assertTrue(found.found().isEmpty());
        }
        assertEquals(1, metadataReads.get(), "an external metadata refusal was skipped");
    }

    @Test
    void pruningTheSourceDoesNotSkipExternalSourcesWithSimilarNames() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource target = resource(TARGET, List.of(), advances, Map.of());
        final Resource external = resource(TARGET + "-sibling", List.of(), advances,
                Map.of("link", TARGET));
        final Resource root = resource("/content", List.of(target, external), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 3);
            assertTrue(found.complete());
            assertEquals(List.of(external), found.found());
        }
    }

    @Test
    void aTargetAtTheContentRootHasNoExternalReferences() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource root = resource("/content", List.of(wideTarget(advances)), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, "/content", 1);
            assertTrue(found.complete());
            assertTrue(found.found().isEmpty());
            assertFalse(RepositoryReach.possiblyReferenced(resolver, "/content", 1));
            assertTrue(RepositoryReach.possiblyReferenced(resolver, "/content", 0));
        }
        assertEquals(0, advances.get());
    }

    @Test
    void aSourceOutsideContentDoesNotPruneAnyVisibleContent() {
        final AtomicInteger advances = new AtomicInteger();
        final String target = "/conf/synthetic-target";
        final Resource nested = resource("/content/synthetic-first/child", List.of(), advances,
                Map.of("links", new String[]{target}));
        final Resource first = resource("/content/synthetic-first", List.of(nested), advances,
                Map.of("link", target));
        final Resource root = resource("/content", List.of(first), advances, Map.of());

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, target, 3);
            assertTrue(found.complete());
            assertEquals(List.of(first, nested), found.found());
        }
        assertEquals(2, advances.get());
    }

    private static Resource wideTarget(AtomicInteger advances) {
        final List<Resource> children = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(child -> resource(TARGET + "/child-" + child, List.of(), advances,
                        Map.of("link", TARGET))).toList();
        return resource(TARGET, children, advances, Map.of("link", TARGET));
    }

    private static Resource unreadableExternal(AtomicInteger metadataReads) {
        final javax.jcr.Node node = (javax.jcr.Node) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]{javax.jcr.Node.class},
                (proxy, method, arguments) -> {
                    if ("getProperties".equals(method.getName())) {
                        metadataReads.incrementAndGet();
                        throw new javax.jcr.RepositoryException("synthetic metadata refusal");
                    }
                    throw new AssertionError("unexpected node call: " + method.getName());
                });
        return (Resource) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                    if ("getPath".equals(method.getName())) {
                        return "/content/synthetic-unreadable";
                    }
                    if ("adaptTo".equals(method.getName())) {
                        return node;
                    }
                    throw new AssertionError("unexpected external call: " + method.getName());
                });
    }

    private static ResourceResolver resolver(Resource root) {
        return (ResourceResolver) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ResourceResolver.class}, (proxy, method, arguments) -> {
                    if ("getResource".equals(method.getName())) {
                        return root;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new AssertionError("unexpected resolver call: " + method.getName());
                });
    }

    private static Resource resource(String path, List<Resource> children, AtomicInteger advances,
                                     Map<String, Object> values) {
        return (Resource) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                    if ("getPath".equals(method.getName())) {
                        return path;
                    }
                    if ("listChildren".equals(method.getName())) {
                        final Iterator<Resource> source = children.iterator();
                        return new Iterator<Resource>() {
                            @Override
                            public boolean hasNext() {
                                return source.hasNext();
                            }

                            @Override
                            public Resource next() {
                                advances.incrementAndGet();
                                return source.next();
                            }
                        };
                    }
                    if ("getValueMap".equals(method.getName())) {
                        return new ValueMapDecorator(values);
                    }
                    if ("toString".equals(method.getName())) {
                        return path;
                    }
                    return null;
                });
    }
}
