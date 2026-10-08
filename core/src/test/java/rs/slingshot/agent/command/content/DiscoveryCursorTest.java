// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.stream.ElapsedTime;

/** The cursor owns a separate resolver with a fixed, non-renewable lifetime. */
@ExtendWith(SlingContextExtension.class)
final class DiscoveryCursorTest {

    private static final String ROOT = "/content/discovery";
    private static final long LIFETIME = 100;
    private static final ResumableWalk.Limits ONE = new ResumableWalk.Limits(1, 1, 100);
    private static final ResumableWalk.Limits ALL = new ResumableWalk.Limits(20, 20, 100);
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger ownedCloses = new AtomicInteger();
    private final AtomicInteger clones = new AtomicInteger();

    @Test
    void completionClosesOnlyTheOwnedResolverExactlyOnce() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source();
                var cursor = open(caller)) {
            final var outcome = cursor.advance(ALL, caller, resource -> true,
                    resource -> Optional.of(resource.getPath()), () -> false, timer());
            assertTrue(assertInstanceOf(ResumableWalk.Page.class, outcome).complete());
            assertEquals(1, ownedCloses.get());
            assertTrue(caller.isLive());
            assertEquals(ResumableWalk.Stop.CLOSED, refusal(cursor.advance(ALL, caller,
                    resource -> true, resource -> Optional.empty(), () -> false, timer())));
        }
        assertEquals(1, clones.get());
        assertEquals(1, ownedCloses.get());
    }

    @Test
    void aPartialPageRetainsItsResolverUntilExplicitRelease() throws LoginException {
        sling.create().resource(ROOT + "/child");
        try (ResourceResolver caller = source(); var cursor = open(caller)) {
            final var outcome = cursor.advance(ONE, caller, resource -> true,
                    resource -> Optional.of(resource.getPath()), () -> false, timer());
            assertFalse(assertInstanceOf(ResumableWalk.Page.class, outcome).complete());
            assertEquals(0, ownedCloses.get());
        }
        assertEquals(1, ownedCloses.get());
    }

    @Test
    void aNewPageDoesNotRenewTheOriginalExpiry() throws LoginException {
        sling.create().resource(ROOT + "/child/grandchild");
        try (ResourceResolver caller = source(); var cursor = open(caller)) {
            clock.set(LIFETIME - 1);
            assertInstanceOf(ResumableWalk.Page.class, cursor.advance(ONE, caller,
                    resource -> true, resource -> Optional.empty(), () -> false, timer()));
            assertFalse(cursor.expire());
            clock.set(LIFETIME);
            assertTrue(cursor.expire());
            assertEquals(1, ownedCloses.get());
            assertEquals(ResumableWalk.Stop.EXPIRED, refusal(cursor.advance(ALL, caller,
                    resource -> true, resource -> Optional.empty(), () -> false, timer())));
            assertTrue(caller.isLive());
        }
        assertEquals(1, ownedCloses.get());
    }

    @Test
    void expiryDuringAVisitDiscardsThePageAndReleasesTheResolver() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(); var cursor = open(caller)) {
            final var outcome = cursor.advance(ALL, caller, resource -> true, resource -> {
                clock.set(LIFETIME);
                return Optional.of(resource.getPath());
            }, () -> false, timer());
            assertEquals(ResumableWalk.Stop.EXPIRED, refusal(outcome));
            assertEquals(1, ownedCloses.get());
        }
    }

    @Test
    void anotherCallerCannotConsumeOrInvalidateTheCursor() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(); var cursor = open(caller);
                ResourceResolver stranger = new IdentityResolver(caller.clone(Map.of()), "stranger")) {
            assertEquals(ResumableWalk.Stop.WRONG_CALLER, refusal(cursor.advance(ALL, stranger,
                    resource -> true, resource -> {
                        throw new AssertionError("another user must not materialize a row");
                    }, () -> false, timer())));
            assertEquals(0, ownedCloses.get());
            assertInstanceOf(ResumableWalk.Page.class, cursor.advance(ALL, caller,
                    resource -> true, resource -> Optional.empty(), () -> false, timer()));
        }
        assertEquals(2, ownedCloses.get());
    }

    @Test
    void cancellationClosesTheOwnedResolver() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(); var cursor = open(caller)) {
            assertEquals(ResumableWalk.Stop.CANCELLED, refusal(cursor.advance(ALL, caller,
                    resource -> true, resource -> Optional.empty(), () -> true, timer())));
            assertEquals(1, ownedCloses.get());
        }
    }

    @Test
    void aMaterializerFailureCannotLeakTheResolver() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(); var cursor = open(caller)) {
            assertThrows(IllegalStateException.class, () -> cursor.advance(ALL, caller,
                    resource -> true, resource -> {
                        throw new IllegalStateException("fixture failure");
                    }, () -> false, timer()));
            assertEquals(1, ownedCloses.get());
            assertTrue(caller.isLive());
        }
    }

    @Test
    void anUnreadableRootDoesNotAcquireAnotherResolver() throws LoginException {
        try (ResourceResolver caller = source()) {
            assertTrue(DiscoveryCursor.open(caller, ROOT, 4, LIFETIME, clock::get).isEmpty());
        }
        assertEquals(0, clones.get());
        assertEquals(0, ownedCloses.get());
    }

    @Test
    void invalidBoundsRefuseBeforeAcquisition() {
        try (ResourceResolver caller = source()) {
            assertThrows(IllegalArgumentException.class,
                    () -> DiscoveryCursor.open(caller, ROOT, -1, LIFETIME, clock::get));
            assertThrows(IllegalArgumentException.class,
                    () -> DiscoveryCursor.open(caller, ROOT, 4, 0, clock::get));
        }
        assertEquals(0, clones.get());
    }

    @Test
    void aCloneWithAnotherIdentityIsClosedBeforeReadingItsRoot() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(owned -> new UnavailableClone(owned,
                CloneFault.WRONG_ACTOR))) {
            assertTrue(DiscoveryCursor.open(caller, ROOT, 4, LIFETIME, clock::get).isEmpty());
            assertEquals(1, ownedCloses.get());
            assertTrue(caller.isLive());
        }
    }

    @Test
    void aRootUnavailableInTheCloneReleasesAcquisition() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(owned -> new UnavailableClone(owned,
                CloneFault.MISSING_ROOT))) {
            assertTrue(DiscoveryCursor.open(caller, ROOT, 4, LIFETIME, clock::get).isEmpty());
            assertEquals(1, ownedCloses.get());
        }
    }

    @Test
    void aProviderFailureDuringAcquisitionReleasesTheClone() {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(owned -> new UnavailableClone(owned,
                CloneFault.READ_FAILURE))) {
            assertThrows(IllegalStateException.class,
                    () -> DiscoveryCursor.open(caller, ROOT, 4, LIFETIME, clock::get));
            assertEquals(1, ownedCloses.get());
            assertTrue(caller.isLive());
        }
    }

    @Test
    void acquisitionTimeCountsAgainstRetention() throws LoginException {
        sling.create().resource(ROOT);
        try (ResourceResolver caller = source(owned -> {
            clock.set(LIFETIME);
            return owned;
        })) {
            assertTrue(DiscoveryCursor.open(caller, ROOT, 4, LIFETIME, clock::get).isEmpty());
            assertEquals(1, ownedCloses.get());
        }
    }

    @Test
    void anExpirySweepDoesNotWaitForABusyProvider() throws LoginException, InterruptedException,
            ExecutionException, TimeoutException {
        sling.create().resource(ROOT);
        final CountDownLatch visiting = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        try (ResourceResolver caller = source(); var cursor = open(caller);
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            final var result = worker.submit(() -> cursor.advance(ALL, caller, resource -> true,
                    resource -> {
                        visiting.countDown();
                        await(released);
                        return Optional.of(resource.getPath());
                    }, () -> false, timer()));
            try {
                assertTrue(visiting.await(5, TimeUnit.SECONDS));
                clock.set(LIFETIME);
                assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(1), cursor::expire));
                assertEquals(0, ownedCloses.get());
            } finally {
                released.countDown();
            }
            assertEquals(ResumableWalk.Stop.EXPIRED, refusal(result.get(5, TimeUnit.SECONDS)));
            assertEquals(1, ownedCloses.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private DiscoveryCursor<String> open(ResourceResolver caller) throws LoginException {
        return DiscoveryCursor.<String>open(caller, ROOT, 4, LIFETIME, clock::get).orElseThrow();
    }

    private ResourceResolver source() {
        return source(Function.identity());
    }

    private ResourceResolver source(Function<ResourceResolver, ResourceResolver> decorate) {
        try {
            sling.resourceResolver().commit();
            return new CloneSource(sling.resourceResolver().clone(Map.of()), ownedCloses, clones,
                    decorate);
        } catch (final PersistenceException | LoginException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ResumableWalk.Stop refusal(ResumableWalk.Outcome<String> outcome) {
        return assertInstanceOf(ResumableWalk.Refused.class, outcome).reason();
    }

    private static ElapsedTime timer() {
        return ElapsedTime.start(() -> 0);
    }

    private static final class CloneSource extends ResourceResolverWrapper {
        private final AtomicInteger closes;
        private final AtomicInteger clones;
        private final Function<ResourceResolver, ResourceResolver> decorate;

        private CloneSource(ResourceResolver resolver, AtomicInteger closes, AtomicInteger clones,
                            Function<ResourceResolver, ResourceResolver> decorate) {
            super(resolver);
            this.closes = closes;
            this.clones = clones;
            this.decorate = decorate;
        }

        @Override
        public ResourceResolver clone(Map<String, Object> authentication) throws LoginException {
            assertTrue(authentication.isEmpty(), "a cursor cannot supply different authentication");
            clones.incrementAndGet();
            return decorate.apply(new CountedResolver(super.clone(authentication), closes));
        }
    }

    private enum CloneFault {
        WRONG_ACTOR,
        MISSING_ROOT,
        READ_FAILURE
    }

    private static final class UnavailableClone extends ResourceResolverWrapper {
        private final CloneFault fault;

        private UnavailableClone(ResourceResolver resolver, CloneFault fault) {
            super(resolver);
            this.fault = fault;
        }

        @Override
        public String getUserID() {
            return fault == CloneFault.WRONG_ACTOR ? "another-actor" : super.getUserID();
        }

        @Override
        public Resource getResource(String path) {
            return switch (fault) {
                case WRONG_ACTOR -> throw new AssertionError("check identity before reading the clone");
                case MISSING_ROOT -> null;
                case READ_FAILURE -> throw new IllegalStateException("fixture provider failure");
            };
        }
    }

    private static final class CountedResolver extends ResourceResolverWrapper {
        private final AtomicInteger closes;

        private CountedResolver(ResourceResolver resolver, AtomicInteger closes) {
            super(resolver);
            this.closes = closes;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }

    private static final class IdentityResolver extends ResourceResolverWrapper {
        private final String user;

        private IdentityResolver(ResourceResolver resolver, String user) {
            super(resolver);
            this.user = user;
        }

        @Override
        public String getUserID() {
            return user;
        }
    }
}
