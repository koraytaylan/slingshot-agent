// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

/** Reference refusal cost and completeness under one unchanged traversal budget. */
final class ReferencePriorityTraversalTest {

    private static final String TARGET = "/content/synthetic-near/target";

    @Test
    void nearbyReferenceDoesNotInspectTheUnrelatedPrefix() {
        final List<String> inspected = new ArrayList<>();
        final Resource reference = resource("/content/synthetic-near/reference", List.of(),
                Map.of("link", TARGET), inspected);
        final Resource parent = resource("/content/synthetic-near", List.of(reference), Map.of(), inspected);
        final List<Resource> children = new ArrayList<>();
        java.util.stream.IntStream.range(0, 1000).mapToObj(index -> resource(
                "/content/synthetic-unrelated-" + index, List.of(), Map.of(), inspected))
                .forEach(children::add);
        children.add(parent);
        final Resource root = resource("/content", children, Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 1003));
        }
        assertEquals(List.of(parent.getPath(), root.getPath(), reference.getPath()),
                inspected.stream().distinct().toList(),
                "the nearby decisive reference still required scanning the unrelated prefix");
    }

    @Test
    void externalReferenceDoesNotWaitForTheWholePrioritySubtree() {
        final List<String> inspected = new ArrayList<>();
        final List<Resource> children = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> resource("/content/synthetic-near/unrelated-" + index,
                        List.of(), Map.of(), inspected)).toList();
        final Resource parent = resource("/content/synthetic-near", children, Map.of(), inspected);
        final Resource outside = resource("/content/synthetic-outside", List.of(),
                Map.of("link", TARGET), inspected);
        final Resource root = resource("/content", List.of(outside, parent), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 1003));
        }
        final long examined = inspected.stream().distinct().count();
        assertTrue(examined <= 4, () -> "early global reference examined " + examined + " resources");
        assertTrue(inspected.contains(outside.getPath()));
    }

    @Test
    void completeAbsenceUsesEachVisibleResourceOnceWithinTheOriginalBudget() {
        final List<String> inspected = new ArrayList<>();
        try (ResourceResolver resolver = smallTree(inspected, false)) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 4));
        }
        assertEquals(4, inspected.stream().distinct().count());
    }

    @Test
    void exhaustedBudgetStillRefusesUnprovedAbsence() {
        final List<String> inspected = new ArrayList<>();
        try (ResourceResolver resolver = smallTree(inspected, false)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 3));
        }
        assertTrue(inspected.stream().distinct().count() <= 3);
    }

    @Test
    void aReferenceOutsideTheParentSubtreeIsStillFound() {
        final List<String> inspected = new ArrayList<>();
        try (ResourceResolver resolver = smallTree(inspected, true)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 4));
        }
        assertTrue(inspected.contains("/content/synthetic-outside"));
    }

    @Test
    void movesRetainCompleteDiscoveryAndOriginalResultOrdering() {
        final List<String> inspected = new ArrayList<>();
        final Resource outside = resource("/content/synthetic-outside", List.of(),
                Map.of("link", TARGET), inspected);
        final Resource nearby = resource("/content/synthetic-near/reference", List.of(),
                Map.of("link", TARGET), inspected);
        final Resource parent = resource("/content/synthetic-near", List.of(nearby), Map.of(), inspected);
        final Resource root = resource("/content", List.of(outside, parent), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            final RepositoryReach.References found = RepositoryReach.references(resolver, TARGET, 4);
            assertTrue(found.complete());
            assertEquals(List.of(outside, nearby), found.found());
        }
    }

    @Test
    void aProviderAliasFallsBackToTheCompleteGlobalWalk() {
        final List<String> inspected = new ArrayList<>();
        final Resource child = resource("/content/synthetic-outside", List.of(), Map.of(), inspected);
        final Resource root = resource("/content", List.of(child), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root,
                "/content/synthetic-near", root))) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 2));
        }
        assertEquals(2, inspected.stream().distinct().count());
    }

    @Test
    void anUnavailableDirectParentLookupRetainsTheGlobalWalk() {
        final List<String> inspected = new ArrayList<>();
        final Resource child = resource("/content/synthetic-outside", List.of(), Map.of(), inspected);
        final Resource root = resource("/content", List.of(child), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root))) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 2));
        }
    }

    @Test
    void targetDescendantsRemainPrunedInThePriorityWalk() {
        final List<String> inspected = new ArrayList<>();
        final Resource descendant = resource(TARGET + "/child", List.of(), Map.of("link", TARGET), inspected);
        final Resource target = resource(TARGET, List.of(descendant), Map.of(), inspected);
        final Resource parent = resource("/content/synthetic-near", List.of(target), Map.of(), inspected);
        final Resource root = resource("/content", List.of(parent), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, TARGET, 3));
        }
        assertFalse(inspected.contains(descendant.getPath()));
    }

    @Test
    void aTargetOutsideContentDoesNotAddAnOutsideLookup() {
        final List<String> inspected = new ArrayList<>();
        final Resource root = resource("/content", List.of(), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root))) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, "/etc/synthetic-target", 1));
        }
        assertEquals(List.of("/content"), inspected.stream().distinct().toList());
    }

    @Test
    void unreadablePriorityMetadataCannotProveAbsence() {
        final List<String> inspected = new ArrayList<>();
        final Node unreadable = (Node) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{Node.class}, (proxy, method, arguments) -> {
                    throw new RepositoryException("synthetic-unreadable");
                });
        final Resource parent = (Resource) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                        if ("getPath".equals(method.getName())) {
                            return "/content/synthetic-near";
                        }
                        if ("adaptTo".equals(method.getName())) {
                            return unreadable;
                        }
                        if ("getValueMap".equals(method.getName())) {
                            return new ValueMapDecorator(Map.of());
                        }
                        return List.<Resource>of().iterator();
                    });
        final Resource root = resource("/content", List.of(parent), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 2));
        }
    }

    @Test
    void aShallowNearbyReferenceDoesNotWaitForAnUnrelatedDeepBranch() {
        final List<String> inspected = new ArrayList<>();
        final List<Resource> leaves = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> resource("/content/synthetic-near/unrelated/leaf-" + index,
                        List.of(), Map.of(), inspected)).toList();
        final Resource unrelated = resource("/content/synthetic-near/unrelated", leaves, Map.of(), inspected);
        final Resource reference = resource("/content/synthetic-near/reference", List.of(),
                Map.of("link", TARGET), inspected);
        final Resource parent = resource("/content/synthetic-near", List.of(unrelated, reference),
                Map.of(), inspected);
        final Resource root = resource("/content", List.of(parent), Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 10));
        }
        assertTrue(inspected.contains(reference.getPath()),
                "refusal exhausted its budget before the nearby reference");
        assertTrue(inspected.stream().distinct().count() <= 4);
    }

    @Test
    void aShallowGlobalReferenceDoesNotWaitForAnUnrelatedDeepBranch() {
        final List<String> inspected = new ArrayList<>();
        final List<Resource> leaves = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(index -> resource("/content/synthetic-unrelated/leaf-" + index,
                        List.of(), Map.of(), inspected)).toList();
        final Resource unrelated = resource("/content/synthetic-unrelated", leaves, Map.of(), inspected);
        final Resource reference = resource("/content/synthetic-reference", List.of(),
                Map.of("link", TARGET), inspected);
        final Resource parent = resource("/content/synthetic-near", List.of(), Map.of(), inspected);
        final Resource root = resource("/content", List.of(unrelated, reference, parent),
                Map.of(), inspected);
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parent.getPath(), parent))) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, TARGET, 10));
        }
        assertTrue(inspected.contains(reference.getPath()),
                "global reference waited for unrelated descendants");
        assertTrue(inspected.stream().distinct().count() <= 4);
    }

    private enum FilterReadPoint {
        PATH, NEXT, HAS_NEXT
    }

    private enum FilterBoundary {
        INCLUSIVE, EXPIRED
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PATH, INCLUSIVE", "PATH, EXPIRED",
            "NEXT, INCLUSIVE", "NEXT, EXPIRED", "HAS_NEXT, INCLUSIVE", "HAS_NEXT, EXPIRED"})
    void skippingThePriorityChildDoesNotReadAnotherProviderAfterExpiry(FilterReadPoint point,
            FilterBoundary boundary) {
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger afterExpiry = new
                java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean reached = new
                java.util.concurrent.atomic.AtomicBoolean();
        final Runnable expire = () -> {
            if (reached.compareAndSet(false, true)) {
                clock.set(RepositoryReach.SEARCH_MILLISECONDS
                        + (boundary == FilterBoundary.EXPIRED ? 1 : 0));
            }
        };
        final List<String> inspected = new ArrayList<>();
        final String parentPath = "/content/synthetic-near";
        final Resource parent = resource(parentPath, List.of(), Map.of(), inspected);
        final Resource skipped = (Resource)
                Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                        if ("getPath".equals(method.getName())) {
                            if (clock.get() > RepositoryReach.SEARCH_MILLISECONDS) {
                                afterExpiry.incrementAndGet();
                            }
                            if (point == FilterReadPoint.PATH) {
                                expire.run();
                            }
                            return parentPath;
                        }
                        throw new AssertionError("unexpected skipped resource read");
                    });
        final Resource outside = (Resource)
                Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                        if (clock.get() > RepositoryReach.SEARCH_MILLISECONDS) {
                            afterExpiry.incrementAndGet();
                        }
                        if ("getPath".equals(method.getName())) {
                            return "/content/synthetic-outside";
                        }
                        if ("getValueMap".equals(method.getName())) {
                            return new ValueMapDecorator(Map.of());
                        }
                        if ("listChildren".equals(method.getName())) {
                            return List.<Resource>of().iterator();
                        }
                        return null;
                    });
        final java.util.Iterator<Resource> children = List.of(skipped, outside).iterator();
        final java.util.Iterator<Resource> watched = new java.util.Iterator<>() {
            @Override
            public boolean hasNext() {
                if (clock.get() > RepositoryReach.SEARCH_MILLISECONDS) {
                    afterExpiry.incrementAndGet();
                }
                if (point == FilterReadPoint.HAS_NEXT) {
                    expire.run();
                }
                return children.hasNext();
            }

            @Override
            public Resource next() {
                if (clock.get() > RepositoryReach.SEARCH_MILLISECONDS) {
                    afterExpiry.incrementAndGet();
                }
                if (point == FilterReadPoint.NEXT) {
                    expire.run();
                }
                return children.next();
            }
        };
        final Resource root = (Resource)
                Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                        if ("getPath".equals(method.getName())) {
                            return "/content";
                        }
                        if ("getValueMap".equals(method.getName())) {
                            return new ValueMapDecorator(Map.of());
                        }
                        if ("listChildren".equals(method.getName())) {
                            return watched;
                        }
                        return null;
                    });
        try (ResourceResolver resolver = resolver(Map.of("/content", root, parentPath, parent))) {
            assertEquals(boundary == FilterBoundary.EXPIRED, RepositoryReach.possiblyReferenced(resolver,
                    TARGET, 8, rs.slingshot.agent.stream.ElapsedTime.start(clock::get)));
        }
        assertTrue(reached.get(), "the skipped child's path boundary must be reached");
        assertEquals(0, afterExpiry.get(), "a filtered iterator performed provider calls after expiry");
    }

    private static ResourceResolver smallTree(List<String> inspected, boolean externalReference) {
        final Resource outside = resource("/content/synthetic-outside", List.of(),
                externalReference ? Map.of("link", TARGET) : Map.of(), inspected);
        final Resource child = resource("/content/synthetic-near/unrelated", List.of(), Map.of(), inspected);
        final Resource parent = resource("/content/synthetic-near", List.of(child), Map.of(), inspected);
        final Resource root = resource("/content", List.of(outside, parent), Map.of(), inspected);
        return resolver(Map.of("/content", root, parent.getPath(), parent));
    }

    private static ResourceResolver resolver(Map<String, Resource> resources) {
        return (ResourceResolver) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ResourceResolver.class}, (proxy, method, arguments) -> {
                    if ("getResource".equals(method.getName())) {
                        return resources.get(arguments[0]);
                    }
                    return null;
                });
    }

    private static Resource resource(String path, List<Resource> children, Map<String, Object> values,
                                     List<String> inspected) {
        return (Resource) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                    new Class<?>[]{Resource.class}, (proxy, method, arguments) -> {
                        if ("getPath".equals(method.getName())) {
                            return path;
                        }
                        if ("listChildren".equals(method.getName())) {
                            return children.iterator();
                        }
                        if ("getValueMap".equals(method.getName())) {
                            inspected.add(path);
                            return new ValueMapDecorator(values);
                        }
                        if ("toString".equals(method.getName())) {
                            return path;
                        }
                        return null;
                    });
    }
}
