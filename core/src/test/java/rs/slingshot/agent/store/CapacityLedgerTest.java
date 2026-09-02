// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
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
 * <p>The bounds are exercised at exactly the number an admission compares against rather than at
 * the contract's own: the difference between the two is the margin a sharded count may understate
 * by while advances are in flight, and admitting up to the declared bound would be admitting past
 * it on a cluster. A decision may be conservative and may never be wrong.</p>
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
            assertInstanceOf(CapacityLedger.Admitted.class,
                    CapacityLedger.admit(session, SMALL, caller, 1, CONTRACT),
                    "work inside the bound was refused at " + admitted);
            admitted = admitted + 1;
        }
        final CapacityLedger.Refused refused = assertInstanceOf(CapacityLedger.Refused.class,
                CapacityLedger.admit(session, SMALL, caller, 1, CONTRACT),
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
            CapacityLedger.admit(session, SMALL, first, 1, CONTRACT);
            admitted = admitted + 1;
        }
        assertEquals(CapacityLedger.Reached.THE_CALLERS_SHARE,
                assertInstanceOf(CapacityLedger.Refused.class,
                        CapacityLedger.admit(session, SMALL, first, 1, CONTRACT)).reached());
        assertInstanceOf(CapacityLedger.Admitted.class,
                CapacityLedger.admit(session, SMALL, second, 1, CONTRACT),
                "a caller under their own share was refused because another caller was busy");
    }

    @Test
    @DisplayName("what a reservation gives back is exactly what it took")
    void areleasedReservationGivesBackExactly() throws RepositoryException {
        final Session session = prepared();
        final StatePath.Caller caller = caller("the-reserving-caller");
        CapacityLedger.prepare(session, SMALL, caller);
        assertInstanceOf(CapacityLedger.Admitted.class,
                CapacityLedger.admit(session, SMALL, caller, 3, CONTRACT));
        assertEquals(3, CapacityLedger.held(session, SMALL, CONTRACT));
        assertEquals(3, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT));
        CapacityLedger.release(session, SMALL, caller, 3, CONTRACT);
        assertEquals(0, CapacityLedger.held(session, SMALL, CONTRACT),
                "a released reservation left something behind in the total");
        assertEquals(0, CapacityLedger.heldBy(session, SMALL, caller, CONTRACT),
                "a released reservation left something behind in the caller's share");
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
                CapacityLedger.admit(session, streams, caller, 1, CONTRACT);
                mine = mine + 1;
                admitted = admitted + 1;
            }
        }
        assertEquals(admissible, CapacityLedger.held(session, streams, CONTRACT));
        final CapacityLedger.Refused refused = assertInstanceOf(CapacityLedger.Refused.class,
                CapacityLedger.admit(session, streams, callers.getLast(), 1, CONTRACT),
                "work past the store's own total was admitted");
        assertEquals(CapacityLedger.Reached.THE_TOTAL, refused.reached());
        assertTrue(refused.rendered().contains("what this store may hold"), refused.rendered());
    }

    @Test
    @DisplayName("an admission compares against the bound less what a sharded count may understate")
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
        final CapacityLedger.Admission admission =
                CapacityLedger.admit(session, SMALL, caller, 1, CONTRACT);
        assertInstanceOf(CapacityLedger.NotCounted.class, admission,
                "a store with no counters answered as though it were full");
        assertTrue(CapacityLedger.refusalIn(admission).isEmpty(),
                "a write that did not happen was reported as a refusal");
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
