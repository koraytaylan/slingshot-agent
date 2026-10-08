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
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Boundary evidence for bounded reference discovery. */
@ExtendWith(SlingContextExtension.class)
final class RepositoryReachTest {

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private final SlingContext repository = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void amoveRenamesThroughTheRepositorysOwnSession()
            throws org.apache.sling.api.resource.PersistenceException {
        repository.create().resource("/content/one/source", Map.of("title", "kept"));
        repository.create().resource("/content/two", Map.of());
        RepositoryReach.moveTo(repository.resourceResolver(), "/content/one/source",
                "/content/two/renamed");
        repository.resourceResolver().commit();
        assertEquals("kept", java.util.Objects.requireNonNull(repository.resourceResolver()
                        .getResource("/content/two/renamed"), "the moved resource")
                .getValueMap().get("title", String.class), "the moved resource lost what it held");
        assertEquals(null, repository.resourceResolver().getResource("/content/one/source"),
                "the source is still there after a move");
    }

    @Test
    void multivalueReferencesAreFound() {
        sling.create().resource("/content/target");
        sling.create().resource("/content/source", Map.of("links",
                new String[]{"/content/other", "/content/target"}));

        final RepositoryReach.References references = RepositoryReach.references(
                sling.resourceResolver(), "/content/target", 10);

        assertTrue(references.complete());
        assertTrue(references.found().stream().anyMatch(resource ->
                "/content/source".equals(resource.getPath())));
    }

    @Test
    void multivalueReferencesAreRepointedEntryByEntry()
            throws org.apache.sling.api.resource.PersistenceException {
        sling.create().resource("/content/source", Map.of("links",
                new String[]{"/content/target", "/content/other", "/content/target"}));

        final Resource source = sling.resourceResolver().getResource("/content/source");
        assertEquals(2, RepositoryReach.repointed(List.of(source), "/content/target",
                "/content/moved"));

        assertEquals(List.of("/content/moved", "/content/other", "/content/moved"),
                List.of(source.getValueMap().get("links", String[].class)));
    }

    @Test
    void exhaustedVisibilityIsNeverReportedComplete() {
        sling.create().resource("/content/target");
        sling.create().resource("/content/source", Map.of("link", "/content/target"));

        final RepositoryReach.References references = RepositoryReach.references(
                sling.resourceResolver(), "/content/target", 1);

        assertFalse(references.complete());
        assertTrue(references.found().isEmpty());
    }

    @Test
    void aWideTreeStopsAtTheBoundWithoutPresentingACompletePrefix() {
        sling.create().resource("/content/wide");
        for (int child = 0; child < 1000; child++) {
            sling.create().resource("/content/wide/child-" + child);
        }

        final List<String> reached = RepositoryReach.under(
                sling.resourceResolver().getResource("/content/wide"), 1);

        assertEquals(2, reached.size(), "a bound-one walk retained more than one node past its bound");
        assertEquals(List.of("/content/wide", "/content/wide/child-0"), reached);
    }

    @Test
    void aDeepTreeKeepsTheActivePathWithinTheBound() {
        String path = "/content/deep";
        sling.create().resource(path);
        final StringBuilder deepest = new StringBuilder(path);
        for (int depth = 0; depth < 100; depth++) {
            deepest.append("/child");
            sling.create().resource(deepest.toString());
        }

        final List<String> reached = RepositoryReach.under(
                sling.resourceResolver().getResource("/content/deep"), 100);

        assertEquals(101, reached.size(), "the deep walk did not stop one node past its bound");
        assertEquals(deepest.toString(), reached.get(reached.size() - 1));
    }

    @Test
    void aWideIteratorIsAdvancedOnlyForTheBoundedPrefix() {
        final AtomicInteger advances = new AtomicInteger();
        final List<Resource> children = new java.util.ArrayList<>();
        for (int child = 0; child < 10_000; child++) {
            children.add(resource("/content/wide/child-" + child, List.of(), advances));
        }

        RepositoryReach.under(resource("/content/wide", children, advances), 1);

        assertEquals(2, advances.get(), "a bound-one walk consumed the whole wide iterator");
    }

    @Test
    void aDeepIteratorAdvancesOnlyAlongTheActivePath() {
        final AtomicInteger advances = new AtomicInteger();
        Resource current = resource("/content/deep/child-100", List.of(), advances);
        for (int depth = 99; depth >= 0; depth--) {
            current = resource("/content/deep" + (depth == 0 ? "" : "/child-" + depth),
                    List.of(current), advances);
        }

        RepositoryReach.under(current, 100);

        assertEquals(100, advances.get(), "a deep walk advanced outside its active path");
    }

    @Test
    void aKnownReferenceEndsADeletionCheckBeforeTheRemainingSiblingsAreRead() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource reference = resource("/content/synthetic-reference", List.of(), advances,
                Map.of("link", "/content/synthetic-target"));
        final List<Resource> children = new java.util.ArrayList<>(List.of(reference));
        java.util.stream.IntStream.range(0, 1000).mapToObj(child ->
                resource("/content/synthetic-unrelated-" + child, List.of(), advances))
                .forEach(children::add);
        final Resource root = resource("/content", children, advances);

        try (ResourceResolver resolver = resolver(root)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, "/content/synthetic-target",
                    1002));
        }
        assertEquals(1, advances.get(), "a known reference still traversed unrelated siblings");
    }

    @Test
    void completeReferenceDiscoveryStillFindsEveryMatchingSibling() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource first = resource("/content/synthetic-first", List.of(), advances,
                Map.of("link", "/content/synthetic-target"));
        final Resource unrelated = resource("/content/synthetic-unrelated", List.of(), advances);
        final Resource last = resource("/content/synthetic-last", List.of(), advances,
                Map.of("links", new String[]{"/content/synthetic-target"}));
        final Resource root = resource("/content", List.of(first, unrelated, last), advances);

        try (ResourceResolver resolver = resolver(root)) {
            final RepositoryReach.References found = RepositoryReach.references(resolver,
                    "/content/synthetic-target", 4);
            assertTrue(found.complete());
            assertEquals(List.of(first, last), found.found());
        }
        assertEquals(3, advances.get(), "a move's discovery stopped before the final sibling");
    }

    @Test
    void absenceRequiresACompleteWalkWithinTheOriginalNodeBudget() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource child = resource("/content/synthetic-unrelated", List.of(), advances);
        final Resource root = resource("/content", List.of(child), advances);

        try (ResourceResolver resolver = resolver(root)) {
            assertTrue(RepositoryReach.possiblyReferenced(resolver, "/content/synthetic-target",
                    0));
            assertTrue(RepositoryReach.possiblyReferenced(resolver, "/content/synthetic-target",
                    1));
            assertFalse(RepositoryReach.possiblyReferenced(resolver, "/content/synthetic-target",
                    2));
        }
    }

    @Test
    void referencesInsideTheRemovedSubtreeDoNotPreventDeletion() {
        final AtomicInteger advances = new AtomicInteger();
        final Resource nested = resource("/content/synthetic-target/child", List.of(), advances,
                Map.of("link", "/content/synthetic-target"));
        final Resource target = resource("/content/synthetic-target", List.of(nested), advances,
                Map.of("link", "/content/synthetic-target"));
        final Resource root = resource("/content", List.of(target), advances);

        try (ResourceResolver resolver = resolver(root)) {
            assertFalse(RepositoryReach.possiblyReferenced(resolver, "/content/synthetic-target",
                    3));
        }
        assertEquals(1, advances.get(), "a self-reference check traversed the removed subtree");
    }

    @Test
    void anAbsentContentRootHasNoVisibleReferences() {
        assertFalse(RepositoryReach.possiblyReferenced(sling.resourceResolver(),
                "/content/synthetic-target", 1));
    }

    private static ResourceResolver resolver(Resource root) {
        return (ResourceResolver) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
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

    private static Resource resource(String path, List<Resource> children, AtomicInteger advances) {
        return resource(path, children, advances, Map.of());
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
                    if ("toString".equals(method.getName())) {
                        return path;
                    }
                    if ("getValueMap".equals(method.getName())) {
                        return new ValueMapDecorator(values);
                    }
                    return null;
                });
    }
}
