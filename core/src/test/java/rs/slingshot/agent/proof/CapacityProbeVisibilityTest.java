// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.ShardedCount;
import rs.slingshot.agent.store.StatePath;

/** The cluster proof waits for all initialization paths and still reports actual accounting errors. */
@ExtendWith(SlingContextExtension.class)
final class CapacityProbeVisibilityTest {

    private static final AgentContract CONTRACT = ((AgentContract.Loaded) AgentContract.load()).contract();
    private static final AccountedQuantity QUANTITY = AccountedQuantity.CONCURRENT_EVENT_STREAMS;
    private static final StatePath.Caller CALLER =
            ((StatePath.Held) StatePath.caller("proof-capacity-owner")).caller();
    private static final String TOTAL = CapacityLedger.totalPath(QUANTITY).path();
    private static final String SHARE = CapacityLedger.callerPath(QUANTITY, CALLER).path();
    private static final String RESERVATIONS = StatePath.deployment(StatePath.CAPACITY)
            .child(CapacityReservation.NODE).path();

    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void missingDeploymentCounterIsNotPrepared() throws IOException {
        view();
        assertEquals(200, sling.response().getStatus());
        assertEquals("unprepared", sling.response().getOutputAsString());
    }

    @Test
    void visibleDeploymentCounterDoesNotProveCallerCounterVisibility() throws IOException {
        sling.create().resource(TOTAL);
        view();
        assertEquals(200, sling.response().getStatus());
        assertEquals("unprepared", sling.response().getOutputAsString());
    }

    @Test
    void visibleCountersDoNotProveReservationTreeVisibility() throws IOException {
        sling.create().resource(TOTAL);
        sling.create().resource(SHARE);
        view();
        assertEquals(200, sling.response().getStatus());
        assertEquals("unprepared", sling.response().getOutputAsString());
    }

    @Test
    void completeEmptyLayoutReportsAllFourZeroCounts() throws IOException {
        layout();
        view();
        assertEquals(200, sling.response().getStatus());
        assertEquals("0/0/0/0", sling.response().getOutputAsString());
    }

    @Test
    void completeActiveLayoutReportsActualHeldAndReservationCounts()
            throws IOException, RepositoryException {
        sling.create().resource(StatePath.ROOT);
        sling.resourceResolver().commit();
        final Session session = session();
        CapacityLedger.prepare(session, QUANTITY, CALLER);
        assertInstanceOf(CapacityLedger.Reserved.class, CapacityLedger.take(session, CALLER,
                List.of(new CapacityReservation.Charge(QUANTITY, 1)), CONTRACT));
        view();
        assertEquals(200, sling.response().getStatus());
        assertEquals("1/1/1/0", sling.response().getOutputAsString());
    }

    @Test
    void corruptVisibleCounterStillFailsInsteadOfBecomingUnprepared()
            throws IOException, RepositoryException {
        layout();
        final Session session = session();
        session.getNode(TOTAL).setProperty(ShardedCount.SHARD_PREFIX + 0, -1L);
        session.save();
        view();
        assertEquals(500, sling.response().getStatus());
        assertTrue(sling.response().getOutputAsString().contains("negative persisted shard"));
    }

    private void layout() {
        sling.create().resource(TOTAL);
        sling.create().resource(SHARE);
        sling.create().resource(RESERVATIONS);
    }

    private Session session() {
        return Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
    }

    private void view() throws IOException {
        sling.resourceResolver().commit();
        sling.request().setParameterMap(Map.of("action", "capacity-view", "fixture", "all"));
        new ExclusiveTransitionProbe().doPost(sling.request(), sling.response());
    }
}
