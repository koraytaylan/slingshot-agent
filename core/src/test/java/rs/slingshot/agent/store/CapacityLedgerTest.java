// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.InvalidItemStateException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * What this store admits, and what it refuses at whose bound.
 *
 * <p>The thresholds retain the existing shard headroom. Independent Oak sessions also prove that
 * competing and interrupted transitions preserve the entire account, because headroom cannot
 * supply atomicity or recover an ignored write conflict.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class CapacityLedgerTest {

    private static final AgentContract CONTRACT = contract();

    /** The quantity whose bounds are small enough to reach in a suite. */
    private static final AccountedQuantity SMALL =
            AccountedQuantity.CONCURRENT_COMMAND_EXECUTIONS;

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    @DisplayName("the quantities this build counts and the bounds the contract declares agree")
    void thequantitiesAndTheBoundsAgree() {
        Arrays.stream(AccountedQuantity.values()).forEach(quantity -> {
            assertTrue(CONTRACT.value(quantity.total()) > 0,
                    quantity.spelling() + " has no total the contract declares");
            assertTrue(CONTRACT.value(quantity.callerShare()) > 0,
                    quantity.spelling() + " has no per-caller share the contract declares");
            assertTrue(CONTRACT.value(quantity.callerShare())
                            <= CONTRACT.value(quantity.total()),
                    quantity.spelling() + " gives one caller more than this store holds");
        });
        final List<String> accounted = AccountedQuantity.accountedBounds();
        final List<String> bounded = Arrays.stream(ContractLimit.values())
                .map(ContractLimit::key)
                .filter(key -> key.startsWith("maximum_current_generation_")
                        || key.startsWith("maximum_caller_current_generation_")
                        || key.endsWith("concurrent_event_streams")
                        || key.endsWith("concurrent_command_executions"))
                .sorted()
                .toList();
        assertEquals(bounded, accounted,
                "a bound is declared that nothing counts, or a quantity is counted that no bound"
                        + " covers");
        assertEquals(16, AccountedQuantity.values().length, "a quantity was added or lost");
        assertTrue(AccountedQuantity.named("nothing-counts-this").isEmpty());
    }

    @Test
    @DisplayName("work is admitted up to the number an admission compares against, and refused past it")
    void thetotalHoldsAtBothSides() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("one-caller");
        CapacityLedger.prepare(session, SMALL, caller);
        final long admissible = SMALL.admissibleCallerShare(CONTRACT);
        assertTrue(admissible > 0, "the suite cannot reach this bound");
        long admitted = 0;
        while (admitted < admissible) {
            assertInstanceOf(CapacityLedger.Reserved.class,
                    CapacityLedger.take(session, caller,
                        List.of(new CapacityReservation.Charge(SMALL, 1)), CONTRACT),
                    "work inside the bound was refused at " + admitted);
            admitted = admitted + 1;
        }
        final CapacityLedger.Refused refused = assertInstanceOf(CapacityLedger.Refused.class,
                CapacityLedger.take(session, caller,
                        List.of(new CapacityReservation.Charge(SMALL, 1)), CONTRACT),
                "work past the bound was admitted");
        assertEquals(CapacityLedger.Reached.THE_CALLERS_SHARE, refused.reached());
        assertEquals(admissible, refused.bound());
        assertTrue(refused.rendered().contains(SMALL.spelling()), refused.rendered());
        assertEquals(admissible, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT),
                "a refused admission left the caller's count above the bound");
        assertEquals(admissible, CapacityLedger.held(session, SMALL, CONTRACT),
                "a refused admission left the total above the bound");
    }

    @Test
    @DisplayName("a caller at their own share is refused while another caller is admitted")
    void onecallerCannotSpendTheStore() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller first = caller("the-busy-caller");
        final StatePath.Caller second = caller("the-other-caller");
        CapacityLedger.prepare(session, SMALL, first);
        CapacityLedger.prepare(session, SMALL, second);
        long admitted = 0;
        while (admitted < SMALL.admissibleCallerShare(CONTRACT)) {
            CapacityLedger.take(session, first,
                        List.of(new CapacityReservation.Charge(SMALL, 1)), CONTRACT);
            admitted = admitted + 1;
        }
        assertEquals(CapacityLedger.Reached.THE_CALLERS_SHARE,
                assertInstanceOf(CapacityLedger.Refused.class,
                        CapacityLedger.take(session, first,
                        List.of(new CapacityReservation.Charge(SMALL, 1)), CONTRACT)).reached());
        assertInstanceOf(CapacityLedger.Reserved.class,
                CapacityLedger.take(session, second,
                        List.of(new CapacityReservation.Charge(SMALL, 1)), CONTRACT),
                "a caller under their own share was refused because another caller was busy");
    }

    @Test
    @DisplayName("what a reservation gives back is exactly what it took")
    void areleasedReservationGivesBackExactly() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("the-reserving-caller");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation reservation = take(session, caller, 3);
        assertEquals(3, CapacityLedger.held(session, SMALL, CONTRACT));
        assertEquals(3, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT));
        CapacityLedger.release(session, reservation, CONTRACT);
        assertEquals(0, CapacityLedger.held(session, SMALL, CONTRACT),
                "a released reservation left something behind in the total");
        assertEquals(0, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT),
                "a released reservation left something behind in the caller's share");
    }

    @Test
    @DisplayName("two independent releases restore both counts even when their saves race")
    void competingReleasesRestoreBothCounts() throws RepositoryException, LoginException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("racing-releases");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation first = take(session, caller, 1);
        final CapacityReservation other = take(session, caller, 1);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = Objects.requireNonNull(resolver.adaptTo(Session.class));
            final Session raced = SaveInterleaving.before(session,
                    () -> CapacityLedger.release(second, other, CONTRACT));
            CapacityLedger.release(raced, first, CONTRACT);
            assertEquals(0, CapacityLedger.held(second, SMALL, CONTRACT));
            assertEquals(0, CapacityLedger.heldBy(second, SMALL, caller, CONTRACT));
        }
    }

    @Test
    @DisplayName("an admission interrupted after its first commit never leaves half an account")
    void interruptedAdmissionKeepsTheAccountsEqual() throws RepositoryException, LoginException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("interrupted-admission");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation reservation = pending(session, caller);
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.reserve(lostReply(session), reservation, CONTRACT));
        try (ResourceResolver resolver = anotherResolver()) {
            final Session observer = Objects.requireNonNull(resolver.adaptTo(Session.class));
            assertEquals(CapacityLedger.held(observer, SMALL, CONTRACT),
                    CapacityLedger.heldBy(observer, SMALL, caller, CONTRACT));
        }
    }

    @Test
    @DisplayName("a release interrupted after its first commit never leaves half an account")
    void interruptedReleaseKeepsTheAccountsEqual() throws RepositoryException, LoginException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("interrupted-release");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation reservation = take(session, caller, 1);
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.release(lostReply(session), reservation, CONTRACT));
        try (ResourceResolver resolver = anotherResolver()) {
            final Session observer = Objects.requireNonNull(resolver.adaptTo(Session.class));
            assertEquals(CapacityLedger.held(observer, SMALL, CONTRACT),
                    CapacityLedger.heldBy(observer, SMALL, caller, CONTRACT));
        }
    }

    @Test
    @DisplayName("a last-slot contender retries its admission against the winner's fresh counts")
    void aLastSlotHasOnlyOneReservation() throws RepositoryException, LoginException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("last-slot");
        CapacityLedger.prepare(session, SMALL, caller);
        final long bound = SMALL.admissibleCallerShare(CONTRACT);
        CapacityLedger.take(session, caller,
                        List.of(new CapacityReservation.Charge(SMALL, bound - 1)), CONTRACT);
        final CapacityReservation first = pending(session, caller);
        final CapacityReservation other = pending(session, caller);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = Objects.requireNonNull(resolver.adaptTo(Session.class));
            final Session raced = SaveInterleaving.before(session, () -> assertInstanceOf(
                    CapacityLedger.Admitted.class,
                    CapacityLedger.reserve(second, other, CONTRACT)));
            assertInstanceOf(CapacityLedger.Refused.class,
                    CapacityLedger.reserve(raced, first, CONTRACT));
            assertEquals(bound, CapacityLedger.held(second, SMALL, CONTRACT));
            assertEquals(bound, CapacityLedger.heldBy(second, SMALL, caller, CONTRACT));
        }
    }

    @Test
    @DisplayName("an automatic refresh between the two counter stamps cannot lose a release")
    void refreshingBetweenCountersPreservesBothReleases()
            throws RepositoryException, LoginException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("refreshed-releases");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation first = take(session, caller, 1);
        final CapacityReservation other = take(session, caller, 1);
        try (ResourceResolver resolver = anotherResolver()) {
            final Session second = Objects.requireNonNull(resolver.adaptTo(Session.class));
            final Session raced = SaveInterleaving.beforeWrite(session,
                    CapacityLedger.callerPath(SMALL, caller).path(),
                    () -> CapacityLedger.release(second, other, CONTRACT));
            CapacityLedger.release(raced, first, CONTRACT);
            assertEquals(0, CapacityLedger.held(second, SMALL, CONTRACT));
            assertEquals(0, CapacityLedger.heldBy(second, SMALL, caller, CONTRACT));
        }
    }

    @Test
    @DisplayName("exhausted contention is surfaced without leaving pending counter changes")
    void exhaustedReleaseContentionIsReported() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("contended-release");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation reservation = take(session, caller, 1);
        final AtomicInteger attempts = new AtomicInteger();
        final Session contended = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new InvalidItemStateException("the competing writer won");
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.release(contended, reservation, CONTRACT));
        assertEquals(CompareAndSet.ATTEMPTS, attempts.get());
        assertFalse(session.hasPendingChanges());
        assertEquals(1, CapacityLedger.held(session, SMALL, CONTRACT));
        assertEquals(1, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT));
    }

    @Test
    @DisplayName("a release exceeding its count refuses without clamping or changing either count")
    void overReleaseCannotCreateCapacity() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("over-release");
        CapacityLedger.prepare(session, SMALL, caller);
        final CapacityReservation reservation = take(session, caller, 1);
        session.getNode(CapacityLedger.callerPath(SMALL, caller).path())
                .setProperty(ShardedCount.SHARD_PREFIX + 0, 0);
        session.save();
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.release(session, reservation, CONTRACT));
        assertFalse(session.hasPendingChanges());
        assertEquals(1, CapacityLedger.held(session, SMALL, CONTRACT));
        assertEquals(0, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT));
        assertTrue(session.nodeExists(reservation.path().path()));
    }

    private static CapacityReservation take(Session session, StatePath.Caller caller, long amount)
            throws RepositoryException {
        return assertInstanceOf(CapacityLedger.Reserved.class, CapacityLedger.take(session, caller,
                List.of(new CapacityReservation.Charge(SMALL, amount)), CONTRACT)).reservation();
    }

    private static CapacityReservation pending(Session session, StatePath.Caller caller)
            throws RepositoryException {
        return CapacityReservation.create(session, CapacityReservation.processOwner(), caller,
                List.of(new CapacityReservation.Charge(SMALL, 1))).orElseThrow();
    }

    private ResourceResolver anotherResolver() throws LoginException {
        return Objects.requireNonNull(sling.getService(ResourceResolverFactory.class))
                .getResourceResolver(Map.of());
    }

    private static Session lostReply(Session session) {
        return (Session) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Session.class}, (proxy, method, arguments) -> {
                    try {
                        final Object returned = method.invoke(session, arguments);
                        if ("save".equals(method.getName())) {
                            throw new RepositoryException("the committed save reply was lost");
                        }
                        return returned;
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
    }

    @Test
    @DisplayName("the total refuses before a caller's share does, when the total is what is reached")
    void thetotalIsReachedFirstWhereItIsSmaller() throws RepositoryException {
        final Session session = prepared();
        final AccountedQuantity streams = AccountedQuantity.CONCURRENT_EVENT_STREAMS;
        final List<StatePath.Caller> callers = List.of(caller("caller-one"), caller("caller-two"),
                caller("caller-three"), caller("caller-four"), caller("caller-five"),
                caller("caller-six"), caller("caller-seven"), caller("caller-eight"),
                caller("caller-nine"), caller("caller-ten"));
        for (final StatePath.Caller caller : callers) {
            CapacityLedger.prepare(session, streams, caller);
        }
        final long admissible = streams.admissibleTotal(CONTRACT);
        long admitted = 0;
        for (final StatePath.Caller caller : callers) {
            long mine = 0;
            while (mine < streams.admissibleCallerShare(CONTRACT) && admitted < admissible) {
                CapacityLedger.take(session, caller,
                        List.of(new CapacityReservation.Charge(streams, 1)), CONTRACT);
                mine = mine + 1;
                admitted = admitted + 1;
            }
        }
        assertEquals(admissible, CapacityLedger.held(session, streams, CONTRACT));
        final CapacityLedger.Refused refused = assertInstanceOf(CapacityLedger.Refused.class,
                CapacityLedger.take(session, callers.getLast(),
                        List.of(new CapacityReservation.Charge(streams, 1)), CONTRACT),
                "work past the store's own total was admitted");
        assertEquals(CapacityLedger.Reached.THE_TOTAL, refused.reached());
        assertTrue(refused.rendered().contains("what this store may hold"), refused.rendered());
    }

    @Test
    @DisplayName("an admission retains the existing conservative shard headroom")
    void themarginIsWhatMakesADecisionConservative() {
        Arrays.stream(AccountedQuantity.values()).forEach(quantity -> {
            assertEquals(CONTRACT.value(quantity.total())
                            - ShardedCount.inFlightMargin(quantity.totalShards(CONTRACT)),
                    quantity.admissibleTotal(CONTRACT));
            assertEquals(CONTRACT.value(quantity.callerShare())
                            - ShardedCount.inFlightMargin(quantity.callerShards(CONTRACT)),
                    quantity.admissibleCallerShare(CONTRACT));
            assertTrue(quantity.admissibleTotal(CONTRACT) > 0,
                    quantity.spelling() + " is sharded past what it may hold");
            assertTrue(quantity.admissibleCallerShare(CONTRACT) > 0,
                    quantity.spelling() + " gives a caller a share smaller than its own margin");
        });
    }

    @Test
    @DisplayName("a count that could not be written is not a refusal, and says so")
    void awriteThatDidNotHappenIsNotARefusal() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("the-uncounted-caller");
        final CapacityLedger.ReservationAdmission admission = CapacityLedger.take(session, caller,
                List.of(new CapacityReservation.Charge(SMALL, 1)), CONTRACT);
        assertInstanceOf(CapacityLedger.NotCounted.class, admission,
                "a store with no counters answered as though it were full");
        assertFalse(session.hasPendingChanges());
    }

    @Test
    void preparationRetriesAnInvisibleParentClaimConflict() throws RepositoryException, LoginException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("preparation-race");
        try (ResourceResolver resolver = anotherResolver()) {
            final Session competing = Objects.requireNonNull(resolver.adaptTo(Session.class));
            CapacityLedger.prepare(SaveInterleaving.before(session, () -> {
                final javax.jcr.Node root = competing.getNode(StatePath.ROOT);
                CompareAndSet.stamp(root);
                root.addNode("unrelated", "nt:unstructured");
                competing.save();
            }), SMALL, caller);
        }
        assertEquals(0, CapacityLedger.held(session, SMALL, CONTRACT));
        assertEquals(0, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT));
    }

    @Test
    void preparationReportsExhaustedContentionAfterFreshAttempts() throws RepositoryException {
        final Session session = prepared();
        final AtomicInteger attempts = new AtomicInteger();
        final Session contended = (Session) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new InvalidItemStateException("preparation contended");
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (final InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        assertThrows(RepositoryException.class,
                () -> CapacityLedger.prepare(contended, SMALL, caller("preparation-refusal")));
        assertEquals(CompareAndSet.ATTEMPTS, attempts.get());
        assertFalse(session.hasPendingChanges());
        assertFalse(session.nodeExists(CapacityLedger.totalPath(SMALL).path()));
    }

    private static StatePath.Caller caller(String name) {
        return assertInstanceOf(StatePath.Held.class, StatePath.caller(name),
                name + " was refused").caller();
    }

    private Session prepared() throws RepositoryException {
        final Session session = java.util.Objects.requireNonNull(
                sling.resourceResolver().adaptTo(Session.class),
                "the resolver has no session, which is a repository that did not start");
        if (!session.nodeExists("/var")) {
            session.getRootNode().addNode("var", "nt:unstructured");
        }
        if (!session.nodeExists(StatePath.ROOT)) {
            session.getNode("/var").addNode("slingshot-agent", "nt:unstructured");
        }
        session.save();
        return session;
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }
}
