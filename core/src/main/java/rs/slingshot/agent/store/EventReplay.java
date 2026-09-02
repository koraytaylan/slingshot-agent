// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;

/**
 * Serving a subscriber the events it has not been shown, and saying so when it cannot be.
 *
 * <p>A replay is filtered to one operation before a cursor is looked at, because the cursor is two
 * numbers and neither of them names an operation: whatever a subscriber sends, the events it can
 * reach are the events of the operation it asked about and no others. That is a property of the
 * shape rather than of a check somebody remembered to write, which is why it is the shape.</p>
 *
 * <p>Three answers, because a subscriber does three different things with them. Events after the
 * cursor are appended to what it knows. A cursor the store can no longer honour — one pointing
 * before the oldest event still kept — is a reset carrying what is currently true, because starting
 * it somewhere else silently would leave it believing it had seen everything in between. A cursor
 * from another incarnation is refused outright, since serving it as an early position is the same
 * lie in a different shape.</p>
 */
public final class EventReplay {

    /** What a cursor is before anything has been shown, which no sequence may be. */
    private static final long BEFORE_THE_FIRST = -1;

    private EventReplay() {
    }

    /**
     * Serves a subscriber everything after its cursor, or tells it why it cannot be.
     *
     * @param session the session to read under
     * @param operation the operation being followed
     * @param cursor where the subscriber got to
     * @param serving the incarnation this store is serving
     * @param contract the authenticated contract, which bounds one read
     * @return what the subscriber is served
     * @throws RepositoryException if the repository fails
     */
    public static ReplayOutcome from(Session session, StatePath operation, ReplayCursor cursor,
                                     EventStoreGeneration serving, AgentContract contract)
            throws RepositoryException {
        if (!cursor.generation().equals(serving)) {
            return new ReplayOutcome.Refused(ReplayOutcome.Refusal.FOREIGN_GENERATION,
                    "this cursor is into generation " + cursor.generation() + " and this store is"
                            + " serving " + serving + ", so what it asks about is gone rather than"
                            + " behind");
        }
        if (!session.nodeExists(operation.path())) {
            return new ReplayOutcome.Refused(ReplayOutcome.Refusal.NO_OPERATION,
                    "there is no operation at " + operation.path() + " for a cursor to be into");
        }
        final SortedMap<Long, String> held = held(session, operation);
        final SnapshotStore.Materialised current = SnapshotStore.read(session, operation);
        if (!held.isEmpty() && held.firstKey() > cursor.sequence().number() + 1) {
            return new ReplayOutcome.Reset(current, "the oldest event still kept is "
                    + held.firstKey() + " and this cursor asks for " + (cursor.sequence().number()
                    + 1) + ", so what is missing between them is gone rather than late");
        }
        return new ReplayOutcome.Served(current,
                after(held, cursor.sequence().number(), contract));
    }

    /**
     * Serves a subscriber that has no cursor at all.
     *
     * <p>It is given what is currently true and the events after the snapshot, which exposes
     * nothing the snapshot already accounts for and retracts nothing it has been told.</p>
     *
     * @param session the session to read under
     * @param operation the operation being followed
     * @param contract the authenticated contract, which bounds one read
     * @return what the subscriber is served
     * @throws RepositoryException if the repository fails
     */
    public static ReplayOutcome current(Session session, StatePath operation,
                                        AgentContract contract) throws RepositoryException {
        if (!session.nodeExists(operation.path())) {
            return new ReplayOutcome.Refused(ReplayOutcome.Refusal.NO_OPERATION,
                    "there is no operation at " + operation.path() + " to follow");
        }
        final SnapshotStore.Materialised current = SnapshotStore.read(session, operation);
        final long from = current instanceof final SnapshotStore.Known known
                ? known.snapshot().sequence().number()
                : BEFORE_THE_FIRST;
        return new ReplayOutcome.Served(current, after(held(session, operation), from, contract));
    }

    private static List<String> after(SortedMap<Long, String> held, long from,
                                      AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_SERVER_SENT_EVENT_BUFFER_BYTES);
        final List<String> served = new ArrayList<>();
        long bytes = 0;
        for (final java.util.Map.Entry<Long, String> event : held.entrySet()) {
            final long size = event.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (event.getKey() <= from) {
                continue;
            }
            if (bytes + size > bound) {
                return served;
            }
            bytes = bytes + size;
            served.add(event.getValue());
        }
        return served;
    }

    private static SortedMap<Long, String> held(Session session, StatePath operation)
            throws RepositoryException {
        final SortedMap<Long, String> held = new TreeMap<>();
        final String path = operation.child(EventLedger.NODE).path();
        if (!session.nodeExists(path)) {
            return held;
        }
        final NodeIterator children = session.getNode(path).getNodes();
        while (children.hasNext()) {
            final Node event = children.nextNode();
            held.put(event.getProperty(EventLedger.SEQUENCE).getLong(),
                    event.getProperty(EventLedger.DOCUMENT).getString());
        }
        return held;
    }
}
