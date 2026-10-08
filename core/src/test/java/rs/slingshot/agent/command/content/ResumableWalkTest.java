// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.stream.ElapsedTime;

/** Pages consume one traversal and recheck current authority before materializing any row. */
@ExtendWith(SlingContextExtension.class)
final class ResumableWalkTest {

    private static final String ROOT = "/content/discovery";
    private static final int CHILDREN = 600;
    private static final ResumableWalk.Limits LIMITS = new ResumableWalk.Limits(7, 3, 100);
    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private final Map<String, Integer> consumed = new HashMap<>();

    @Test
    void aLargeTreeIsEnumeratedWithoutConsumingAnyPrefixAgain() {
        final Resource root = sling.create().resource(ROOT);
        final List<String> expected = IntStream.range(0, CHILDREN)
                .mapToObj(index -> ROOT + "/node-" + index).toList();
        expected.forEach(path -> sling.create().resource(path));
        final List<String> found = new ArrayList<>();
        long examined = 0;
        try (var walk = new ResumableWalk<String>(new ObservedResource(root, consumed), 2)) {
            boolean complete = false;
            int pages = 0;
            while (!complete) {
                final ResumableWalk.Page<String> page = page(walk.advance(LIMITS,
                        sling.resourceResolver(), resource -> true,
                        resource -> ROOT.equals(resource.getPath()) ? Optional.empty()
                                : Optional.of(resource.getPath()), () -> false, timer()));
                assertTrue(page.rows().size() <= LIMITS.matches());
                assertTrue(page.examined() <= LIMITS.nodes());
                examined += page.examined();
                found.addAll(page.rows());
                complete = page.complete();
                pages++;
                assertTrue(pages <= CHILDREN, "a retained iterator must make bounded progress");
            }
        }
        assertEquals(CHILDREN + 1, examined);
        assertEquals(expected, found);
        assertEquals(CHILDREN, new HashSet<>(found).size());
        assertEquals(CHILDREN, consumed.size());
        assertTrue(consumed.values().stream().allMatch(count -> count == 1));
    }

    @Test
    void anEmptyPartialPageDoesNotClaimCompletion() {
        final Resource root = sling.create().resource(ROOT);
        IntStream.range(0, CHILDREN).forEach(index -> sling.create().resource(ROOT + "/n" + index));
        try (var walk = new ResumableWalk<String>(root, 2)) {
            final var first = page(walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> Optional.empty(), () -> false, timer()));
            assertTrue(first.rows().isEmpty());
            assertFalse(first.complete());
            assertEquals(ResumableWalk.Boundary.NODE_BUDGET, first.boundary());
            assertEquals(LIMITS.nodes(), first.examined());
        }
    }

    @Test
    void aMonotonicBoundaryRetainsTheNextNodeForTheFollowingPage() {
        final Resource root = sling.create().resource(ROOT);
        sling.create().resource(ROOT + "/child");
        final AtomicLong clock = new AtomicLong();
        try (var walk = new ResumableWalk<String>(root, 2)) {
            final var first = page(walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> {
                        clock.set(LIMITS.milliseconds());
                        return Optional.of(resource.getPath());
                    },
                    () -> false, ElapsedTime.start(clock::get)));
            assertEquals(List.of(ROOT), first.rows());
            assertEquals(ResumableWalk.Boundary.TIME_BUDGET, first.boundary());
            final var second = page(walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> Optional.of(resource.getPath()), () -> false, timer()));
            assertEquals(List.of(ROOT + "/child"), second.rows());
            assertTrue(second.complete());
        }
    }

    @Test
    void cancellationDiscardsThePartialPageAndClosesTheCursor() {
        final Resource root = sling.create().resource(ROOT);
        final AtomicBoolean cancelled = new AtomicBoolean();
        try (var walk = new ResumableWalk<String>(root, 2)) {
            final var answer = walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> {
                        cancelled.set(true);
                        return Optional.of(resource.getPath());
                    },
                    cancelled::get, timer());
            assertEquals(ResumableWalk.Stop.CANCELLED, refused(answer));
            assertEquals(ResumableWalk.Stop.CLOSED, refused(walk.advance(LIMITS,
                    sling.resourceResolver(), resource -> true, resource -> Optional.empty(),
                    () -> false, timer())));
        }
    }

    @Test
    void revokedRootAuthorityPreventsAnyRetainedIteratorAccess() {
        final Resource root = sling.create().resource(ROOT);
        sling.create().resource(ROOT + "/child");
        try (var walk = new ResumableWalk<String>(new ObservedResource(root, consumed), 2);
                ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
                    @Override
                    public Resource getResource(String path) {
                        return null;
                    }
                }) {
            assertEquals(ResumableWalk.Stop.ROOT_UNREADABLE, refused(walk.advance(LIMITS,
                    current, resource -> true, resource -> Optional.of(resource.getPath()),
                    () -> false, timer())));
            assertTrue(consumed.isEmpty());
        }
    }

    @Test
    void aNewlyUnreadableSubtreeIsNotMaterialized() {
        final Resource root = sling.create().resource(ROOT);
        final String hidden = ROOT + "/hidden";
        sling.create().resource(hidden);
        sling.create().resource(hidden + "/secret");
        sling.create().resource(ROOT + "/visible");
        try (var walk = new ResumableWalk<String>(new ObservedResource(root, consumed), 2);
                ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
                    @Override
                    public Resource getResource(String path) {
                        return path.startsWith(hidden) ? null : super.getResource(path);
                    }
                }) {
            final var page = page(walk.advance(LIMITS, current, resource -> true,
                    resource -> Optional.of(resource.getPath()), () -> false, timer()));
            assertEquals(List.of(ROOT, ROOT + "/visible"), page.rows());
            assertTrue(page.complete());
            assertFalse(consumed.containsKey(hidden + "/secret"));
        }
    }

    @Test
    void theDepthBoundaryAcceptsALeafAndRefusesOneMoreDescent() {
        final Resource root = sling.create().resource(ROOT);
        try (var walk = new ResumableWalk<String>(root, 0)) {
            assertTrue(page(walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> Optional.of(resource.getPath()), () -> false, timer())).complete());
        }
        sling.create().resource(ROOT + "/child");
        try (var walk = new ResumableWalk<String>(root, 0)) {
            assertEquals(ResumableWalk.Stop.DEPTH_EXCEEDED, refused(walk.advance(LIMITS,
                    sling.resourceResolver(), resource -> true, resource -> Optional.of(resource.getPath()),
                    () -> false, timer())));
        }
    }

    @Test
    void zeroBudgetsCannotCreateAnEndlessNonprogressingCursor() {
        assertThrows(IllegalArgumentException.class, () -> new ResumableWalk.Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResumableWalk.Limits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResumableWalk.Limits(1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ResumableWalk<String>(sling.create().resource(ROOT), -1));
    }

    @Test
    void currentRequestPropertiesAreUsedAfterAContentChange() {
        final Resource root = sling.create().resource(ROOT);
        final String child = ROOT + "/child";
        final Resource original = sling.create().resource(child, "title", "old");
        try (var walk = new ResumableWalk<String>(root, 2)) {
            final var first = page(walk.advance(new ResumableWalk.Limits(1, 1, 100),
                    sling.resourceResolver(), resource -> true, resource -> Optional.empty(),
                    () -> false, timer()));
            assertFalse(first.complete());
            sling.resourceResolver().delete(original);
            sling.create().resource(child, "title", "new");
            final var next = page(walk.advance(LIMITS, sling.resourceResolver(), resource -> true,
                    resource -> Optional.ofNullable(resource.getValueMap().get("title", String.class)),
                    () -> false, timer()));
            assertEquals(List.of("new"), next.rows());
            assertTrue(next.complete());
        } catch (final org.apache.sling.api.resource.PersistenceException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void rootPermissionRevocationBetweenPagesClosesBeforeConsumingAnotherChild() {
        final Resource root = sling.create().resource(ROOT);
        sling.create().resource(ROOT + "/child");
        try (var walk = new ResumableWalk<String>(new ObservedResource(root, consumed), 2);
                ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
                    @Override
                    public Resource getResource(String path) {
                        return ROOT.equals(path) ? null : super.getResource(path);
                    }
                }) {
            page(walk.advance(new ResumableWalk.Limits(1, 1, 100), sling.resourceResolver(),
                    resource -> true, resource -> Optional.empty(), () -> false, timer()));
            assertEquals(ResumableWalk.Stop.ROOT_UNREADABLE, refused(walk.advance(LIMITS,
                    current, resource -> true, resource -> Optional.of(resource.getPath()),
                    () -> false, timer())));
            assertTrue(consumed.isEmpty());
        }
    }

    @Test
    void aProviderCannotRedirectTheCursorToAnotherSubtree() {
        final Resource root = sling.create().resource(ROOT);
        final Resource foreign = sling.create().resource("/content/other");
        try (var walk = new ResumableWalk<String>(root, 2);
                ResourceResolver current = new ResourceResolverWrapper(sling.resourceResolver()) {
                    @Override
                    public Resource getResource(String path) {
                        return foreign;
                    }
                }) {
            assertEquals(ResumableWalk.Stop.SOURCE_CHANGED, refused(walk.advance(LIMITS,
                    current, resource -> true, resource -> Optional.of(resource.getPath()),
                    () -> false, timer())));
        }
    }

    @Test
    void anIteratorCannotCrossTheSelectedRoot() {
        final Resource root = sling.create().resource(ROOT);
        final Resource foreign = sling.create().resource("/content/other");
        final Resource redirected = new ResourceWrapper(root) {
            @Override
            public Iterator<Resource> listChildren() {
                return List.of(foreign).iterator();
            }
        };
        try (var walk = new ResumableWalk<String>(redirected, 2)) {
            assertEquals(ResumableWalk.Stop.SOURCE_CHANGED, refused(walk.advance(LIMITS,
                    sling.resourceResolver(), resource -> true, resource -> Optional.of(resource.getPath()),
                    () -> false, timer())));
        }
    }

    @Test
    void aMapperFailureClosesInsteadOfSilentlySkippingTheConsumedNode() {
        final Resource root = sling.create().resource(ROOT);
        try (var walk = new ResumableWalk<String>(root, 2)) {
            assertThrows(IllegalStateException.class, () -> walk.advance(LIMITS,
                    sling.resourceResolver(), resource -> true, resource -> {
                        throw new IllegalStateException("fixture mapper failed");
                    }, () -> false, timer()));
            assertEquals(ResumableWalk.Stop.CLOSED, refused(walk.advance(LIMITS,
                    sling.resourceResolver(), resource -> true, resource -> Optional.empty(),
                    () -> false, timer())));
        }
    }

    private static ElapsedTime timer() {
        return ElapsedTime.start(() -> 0);
    }

    private static <R> ResumableWalk.Page<R> page(ResumableWalk.Outcome<R> answer) {
        assertInstanceOf(ResumableWalk.Page.class, answer);
        return switch (answer) {
            case ResumableWalk.Page<R> page -> page;
            case ResumableWalk.Refused<R> refused -> throw new AssertionError(refused.reason());
        };
    }

    private static ResumableWalk.Stop refused(ResumableWalk.Outcome<?> answer) {
        return assertInstanceOf(ResumableWalk.Refused.class, answer).reason();
    }

    private static final class ObservedResource extends ResourceWrapper {
        private final Map<String, Integer> consumed;

        private ObservedResource(Resource resource, Map<String, Integer> consumed) {
            super(resource);
            this.consumed = consumed;
        }

        @Override
        public Iterator<Resource> listChildren() {
            final Iterator<Resource> underlying = super.listChildren();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return underlying.hasNext();
                }

                @Override
                public Resource next() {
                    final Resource resource = underlying.next();
                    consumed.merge(resource.getPath(), 1, Integer::sum);
                    return new ObservedResource(resource, consumed);
                }
            };
        }
    }
}
