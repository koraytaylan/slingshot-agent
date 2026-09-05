// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.contract.AgentContract;

/** Legacy shard layouts must remain visible and releasable after authenticated bounds change. */
@ExtendWith(SlingContextExtension.class)
final class CapacityLayoutTest {

    private static final AgentContract CONTRACT =
            assertInstanceOf(AgentContract.Loaded.class, AgentContract.load()).contract();
    private static final AccountedQuantity QUANTITY = AccountedQuantity.EVENT_BYTES;
    private static final StatePath.Caller CALLER =
            assertInstanceOf(StatePath.Held.class, StatePath.caller("layout-owner")).caller();
    private final SlingContext sling = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void loweredBoundsCannotHideLegacyShardsOrAdmitPastTheBound() throws RepositoryException, IOException {
        final Session session = prepared();
        take(session, 7);
        legacy(session, 7, 15);
        final AgentContract lowered = bounded(16);
        counts(session, lowered, 7);
        assertInstanceOf(CapacityLedger.Refused.class, CapacityLedger.take(session, CALLER,
                List.of(new CapacityReservation.Charge(QUANTITY, 14)), lowered));
        counts(session, CONTRACT, 7);
    }

    @Test
    void releaseAfterShrinkingBoundsPreservesAnotherReservation() throws RepositoryException, IOException {
        final Session session = prepared();
        final CapacityReservation first = take(session, 7);
        take(session, 3);
        legacy(session, 10, 15);
        CapacityLedger.release(session, first, bounded(16));
        counts(session, CONTRACT, 3);
        counts(session, bounded(16), 3);
        assertFalse(session.getNode(CapacityLedger.totalPath(QUANTITY).path()).hasProperty("shard_15"));
    }

    @Test
    void releaseAfterGrowingBoundsUsesThePersistedQuantity() throws RepositoryException, IOException {
        final Session session = prepared();
        final CapacityReservation reservation = assertInstanceOf(CapacityLedger.Reserved.class,
                CapacityLedger.take(session, CALLER,
                        List.of(new CapacityReservation.Charge(QUANTITY, 7)), bounded(16))).reservation();
        CapacityLedger.release(session, reservation, bounded(4096));
        counts(session, CONTRACT, 0);
    }

    @Test
    void corruptNegativeShardsAreRefusedRatherThanOffsettingOtherCharges() throws RepositoryException {
        final Session session = prepared();
        legacy(session, -1, 15);
        assertThrows(RepositoryException.class, () -> CapacityLedger.held(session, QUANTITY, CONTRACT));
        assertThrows(RepositoryException.class, () -> take(session, 7));
        assertEquals(-1, session.getNode(CapacityLedger.totalPath(QUANTITY).path())
                .getProperty("shard_15").getLong());
    }

    @Test
    void aPaidPromiseCanTransferAfterTheBoundFallsBelowCurrentUsage()
            throws RepositoryException, IOException {
        transferAfterBoundsChange(7, true);
    }

    @Test
    void transferCannotUseAPaidPromiseToIncreaseUsagePastTheLoweredBound()
            throws RepositoryException, IOException {
        transferAfterBoundsChange(8, false);
    }

    private void transferAfterBoundsChange(long amount, boolean accepted)
            throws RepositoryException, IOException {
        final Session session = prepared();
        final CapacityReservation original = take(session, 7);
        take(session, 7);
        final Node source = session.getNode(StatePath.ROOT).addNode("source", "nt:unstructured");
        CapacityReservation.retain(session, original, source);
        session.save();
        final CapacityReservation replacement = CapacityReservation.create(session,
                CapacityReservation.processOwner(), CALLER,
                List.of(new CapacityReservation.Charge(QUANTITY, amount))).orElseThrow();
        final AgentContract lowered = bounded(4);
        try (CapacityReservation.Guard guard = replacement.guard(session, lowered)) {
            final Node destination = session.getNode(StatePath.ROOT)
                    .addNode("destination", "nt:unstructured");
            final CapacityLedger.Admission outcome =
                    CapacityLedger.transfer(session, source, destination, guard.reservation(), lowered);
            if (accepted) {
                assertInstanceOf(CapacityLedger.Admitted.class, outcome);
                session.save();
            } else {
                assertInstanceOf(CapacityLedger.Refused.class, outcome);
            }
        }
        counts(session, lowered, 14);
        assertEquals(accepted, session.nodeExists(StatePath.ROOT + "/destination"));
    }

    private static CapacityReservation take(Session session, long amount) throws RepositoryException {
        return assertInstanceOf(CapacityLedger.Reserved.class, CapacityLedger.take(session, CALLER,
                List.of(new CapacityReservation.Charge(QUANTITY, amount)), CONTRACT)).reservation();
    }

    private static void counts(Session session, AgentContract contract, long expected)
            throws RepositoryException {
        assertEquals(expected, CapacityLedger.held(session, QUANTITY, contract));
        assertEquals(expected, CapacityLedger.heldBy(session, QUANTITY, CALLER, contract));
    }

    private static void legacy(Session session, long amount, int shard) throws RepositoryException {
        for (final StatePath path : List.of(CapacityLedger.totalPath(QUANTITY),
                CapacityLedger.callerPath(QUANTITY, CALLER))) {
            final Node node = session.getNode(path.path());
            int index = 0;
            while (index < ShardedCount.SHARDS) {
                final String property = ShardedCount.SHARD_PREFIX + index;
                if (node.hasProperty(property)) {
                    node.getProperty(property).remove();
                }
                index = index + 1;
            }
            node.setProperty(ShardedCount.SHARD_PREFIX + shard, amount);
        }
        session.save();
    }

    private Session prepared() throws RepositoryException {
        final Session session = Objects.requireNonNull(sling.resourceResolver().adaptTo(Session.class));
        if (!session.nodeExists("/var")) {
            session.getRootNode().addNode("var", "nt:unstructured");
        }
        session.getNode("/var").addNode("slingshot-agent", "nt:unstructured");
        session.save();
        CapacityLedger.prepare(session, QUANTITY, CALLER);
        return session;
    }

    private static AgentContract bounded(long bound) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                AgentContract.class.getResourceAsStream(AgentContract.CONTRACT_RESOURCE))) {
            final StringBuilder rewritten = new StringBuilder();
            new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().forEach(line -> {
                final String key = line.contains("=") ? line.substring(0, line.indexOf('=')).strip() : "";
                rewritten.append(key.equals(QUANTITY.total().key())
                        || key.equals(QUANTITY.callerShare().key())
                        ? key + " = " + bound : line).append('\n');
            });
            final byte[] document = rewritten.toString().getBytes(StandardCharsets.UTF_8);
            return assertInstanceOf(AgentContract.Loaded.class,
                    AgentContract.load(document, AgentContract.digestOf(document))).contract();
        }
    }
}
