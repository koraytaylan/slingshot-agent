// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.wire.EventSequence;
import rs.slingshot.agent.wire.JobEvent;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * What is currently true about one operation, written in the commit that made it true.
 *
 * <p>A disconnected reader that cannot be told what happened while it was away has to be able to
 * ask what is true now, and an answer assembled by folding a ledger on every read is an answer that
 * gets slower as an operation gets longer. So the fold is kept — but kept in the same commit as the
 * event that changes it, which is why there is no method here that writes a snapshot on its own.
 * The other arrangement, a snapshot written by something that runs afterwards, has a window in it,
 * and every window of that shape is a reader told an operation is still running after it
 * finished.</p>
 *
 * <p>That the two agree is checked rather than assumed. {@link #verify} folds the ledger, compares
 * it with the stored snapshot, and compares both with the state the operation's own record claims,
 * in both directions: a record that says finished with no terminal event is a lookup that lies to a
 * subscriber still waiting, and a terminal event under a record that says running is the same lie
 * told the other way round.</p>
 */
public final class SnapshotStore {

    /** The child of an operation the snapshot lives at. */
    public static final String NODE = "snapshot";

    /** The property the newest event's kind is written in. */
    public static final String KIND = "kind";

    /** The property the newest event's sequence is written in. */
    public static final String SEQUENCE = "sequence";

    /** The property the number of events folded into this snapshot is written in. */
    public static final String EVENTS = "event_count";

    /** The property the instant this snapshot was written is written in. */
    public static final String UPDATED_AT = "updated_at_unix_milliseconds";

    /** What a cursor is before a reader has been shown anything, which no sequence may be. */
    private static final long BEFORE_THE_FIRST = -1;

    private SnapshotStore() {
    }

    /**
     * What is currently true about one operation.
     *
     * @param kind the newest event's kind
     * @param sequence the newest event's sequence
     * @param events how many events it has been folded from
     * @param updatedAtUnixMilliseconds when it was last written
     */
    public record Snapshot(JobEventKind kind, EventSequence sequence, long events,
                           long updatedAtUnixMilliseconds) {
    }

    /**
     * What is currently true about one operation, or that nothing is yet.
     *
     * <p>Modelled as a closed pair rather than as an absent value, because "no snapshot" is a real
     * state an operation is in between being recorded and having its first event, and a reader
     * handed a hole has to invent what it means.</p>
     */
    public sealed interface Materialised permits Known, Unwritten {
    }

    /**
     * What is true now.
     *
     * @param snapshot the snapshot
     */
    public record Known(Snapshot snapshot) implements Materialised {
    }

    /** That nothing is true yet, because nothing has happened to this operation. */
    public enum Unwritten implements Materialised {
        /** No event has been appended, so there is nothing to be currently true. */
        NOTHING_HAS_HAPPENED
    }

    /** What the operation's own record claims, where anything holds it. */
    public sealed interface Claimed permits Says, NoRecord {
    }

    /**
     * The state the record claims, as the event kind that state means.
     *
     * @param kind the kind
     */
    public record Says(JobEventKind kind) implements Claimed {
    }

    /** That nothing holds this operation, which is itself something the pass compares against. */
    public enum NoRecord implements Claimed {
        /** There is no record, so anything the ledger says is said under nothing. */
        NOTHING_HOLDS_THIS_OPERATION
    }

    /**
     * What a reader is served: what is true now, and everything it has not been shown.
     *
     * @param current what is true now
     * @param after the events past the cursor, oldest first, as the bytes they were written as
     */
    public record Reading(Materialised current, List<String> after) {

        /** Holds a list nothing can change afterwards. */
        public Reading {
            after = List.copyOf(after);
        }
    }

    /** A way in which the snapshot, the ledger, and the record can fail to say the same thing. */
    public enum Discrepancy {
        /** There are events and no snapshot, so a reader would be told nothing has happened. */
        NO_SNAPSHOT,
        /** There is a snapshot and no events, so a reader would be told about an event nobody has. */
        NO_EVENTS,
        /** The snapshot's kind is not the newest event's kind. */
        KIND_DIFFERS,
        /** The snapshot's sequence is not the newest event's sequence. */
        SEQUENCE_DIFFERS,
        /** The snapshot was folded from a different number of events than the ledger holds. */
        COUNT_DIFFERS,
        /** The record says finished and no event says so, which is a lie to a waiting reader. */
        RECORD_TERMINAL_WITHOUT_EVENT,
        /** An event says finished and the record does not, which is the same lie reversed. */
        EVENT_TERMINAL_WITHOUT_RECORD
    }

    /**
     * One way in which they do not say the same thing, and which operation it is about.
     *
     * @param discrepancy what disagrees
     * @param operation the operation it is about, named so somebody can go and look
     * @param detail what was observed
     */
    public record Finding(Discrepancy discrepancy, String operation, String detail) {
    }

    /** The result of folding the ledger and comparing it with what is stored. */
    public sealed interface Verification permits Agrees, Disagrees {
    }

    /**
     * A snapshot that is the fold of the ledger, under a record that says the same thing.
     *
     * @param snapshot what is stored, which is also what the fold produces
     */
    public record Agrees(Materialised snapshot) implements Verification {
    }

    /**
     * Everything that does not agree, each named.
     *
     * @param findings the findings, in the order they were checked
     */
    public record Disagrees(List<Finding> findings) implements Verification {

        /** Holds findings nothing can change afterwards. */
        public Disagrees {
            findings = List.copyOf(findings);
        }
    }

    /**
     * Appends one event and moves the snapshot with it, in one commit or not at all.
     *
     * @param session the session to write under
     * @param caller whose share the room comes out of
     * @param event the event
     * @param canonical the event's canonical bytes
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @return what appending it did, which is what the ledger said
     * @throws RepositoryException if the repository fails
     */
    public static EventLedger.Outcome record(Session session, StatePath.Caller caller,
                                             JobEvent event, byte[] canonical,
                                             long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        return record(session, caller, event, canonical, nowUnixMilliseconds, contract,
                (written, appended) -> { });
    }

    /**
     * Appends one event, moves the snapshot with it, and writes whatever else belongs in the same
     * commit.
     *
     * <p>The extra writer exists for the one caller that has more than a snapshot to land at the
     * same instant — the commit that ends an operation, whose state, answer, event, and snapshot
     * are four representations of one fact. It cannot be used to write a snapshot on its own: what
     * it is handed is an event that is being written, and the snapshot moves whether the caller
     * asks for it or not.</p>
     *
     * @param session the session to write under
     * @param caller whose share the room comes out of
     * @param event the event
     * @param canonical the event's canonical bytes
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @param alongside what else the commit writes
     * @return what appending it did, which is what the ledger said
     * @throws RepositoryException if the repository fails
     */
    public static EventLedger.Outcome record(Session session, StatePath.Caller caller,
                                             JobEvent event, byte[] canonical,
                                             long nowUnixMilliseconds, AgentContract contract,
                                             EventLedger.Alongside alongside)
            throws RepositoryException {
        return EventLedger.append(session, caller, event, canonical, nowUnixMilliseconds, contract,
                (written, appended) -> {
                    materialise(written, appended, nowUnixMilliseconds);
                    alongside.write(written, appended);
                });
    }

    private static void materialise(Session session, JobEvent event, long nowUnixMilliseconds)
            throws RepositoryException {
        final StatePath operation = StatePath.operation(event.generation(), event.identifier());
        final String path = operation.child(NODE).path();
        final Node snapshot = session.nodeExists(path)
                ? session.getNode(path)
                : session.getNode(operation.path()).addNode(NODE, "nt:unstructured");
        snapshot.setProperty(KIND, event.kind().spelling());
        snapshot.setProperty(SEQUENCE, event.sequence().number());
        snapshot.setProperty(EVENTS, event.sequence().number() + 1);
        snapshot.setProperty(UPDATED_AT, nowUnixMilliseconds);
    }

    /**
     * What is currently true about one operation, where anything is.
     *
     * @param session the session to read under
     * @param operation the operation
     * @return the snapshot, or nothing where no event has happened yet
     * @throws RepositoryException if the repository fails
     */
    public static Materialised read(Session session, StatePath operation)
            throws RepositoryException {
        final String path = operation.child(NODE).path();
        if (!session.nodeExists(path)) {
            return Unwritten.NOTHING_HAS_HAPPENED;
        }
        final Node stored = session.getNode(path);
        final Optional<JobEventKind> kind =
                JobEventKind.named(stored.getProperty(KIND).getString());
        final EventSequence.Outcome sequence =
                EventSequence.of(stored.getProperty(SEQUENCE).getLong());
        if (kind.isEmpty() || sequence instanceof EventSequence.Refused) {
            return Unwritten.NOTHING_HAS_HAPPENED;
        }
        return new Known(new Snapshot(kind.get(), ((EventSequence.Held) sequence).sequence(),
                stored.getProperty(EVENTS).getLong(),
                stored.getProperty(UPDATED_AT).getLong()));
    }

    /**
     * Serves a reader that has been shown nothing yet.
     *
     * <p>It is served the snapshot and the events after it, which is nothing where the snapshot is
     * current — and the snapshot is always current, because it is written in the commit that made
     * it so. What it is never served is an event at or below the sequence the snapshot already
     * accounts for, because that would be the same fact told twice.</p>
     *
     * @param session the session to read under
     * @param operation the operation
     * @return the snapshot and the events after it
     * @throws RepositoryException if the repository fails
     */
    public static Reading current(Session session, StatePath operation)
            throws RepositoryException {
        final Materialised snapshot = read(session, operation);
        final long from = snapshot instanceof final Known known
                ? known.snapshot().sequence().number()
                : BEFORE_THE_FIRST;
        return new Reading(snapshot, after(session, operation, from));
    }

    /**
     * Serves a reader resuming from where it was last shown.
     *
     * @param session the session to read under
     * @param operation the operation
     * @param cursor what the reader has already been shown
     * @return the snapshot and the events after the cursor
     * @throws RepositoryException if the repository fails
     */
    public static Reading since(Session session, StatePath operation, EventSequence cursor)
            throws RepositoryException {
        return new Reading(read(session, operation),
                after(session, operation, cursor.number()));
    }

    private static List<String> after(Session session, StatePath operation, long from)
            throws RepositoryException {
        final List<String> after = new ArrayList<>();
        final List<String> held = EventLedger.held(session, operation.child(EventLedger.NODE));
        long sequence = 0;
        while (sequence < held.size()) {
            if (sequence > from) {
                after.add(held.get((int) sequence));
            }
            sequence = sequence + 1;
        }
        return after;
    }

    /**
     * Folds the ledger and compares it with the snapshot and with the record's own state.
     *
     * @param session the session to read under
     * @param operation the operation
     * @param recordState the state the operation's record claims, as the event kind it means
     * @return that they agree, or everything that does not
     * @throws RepositoryException if the repository fails
     */
    public static Verification verify(Session session, StatePath operation, Claimed recordState)
            throws RepositoryException {
        final List<Finding> findings = new ArrayList<>();
        final Materialised stored = read(session, operation);
        final Materialised folded = fold(session, operation);
        againstTheLedger(operation, stored, folded, findings);
        againstTheRecord(operation, folded, recordState, findings);
        return findings.isEmpty() ? new Agrees(stored) : new Disagrees(findings);
    }

    private static void againstTheLedger(StatePath operation, Materialised stored,
                                         Materialised folded, List<Finding> findings) {
        final String named = operation.path();
        if (!(stored instanceof final Known held)) {
            if (folded instanceof final Known counted) {
                findings.add(new Finding(Discrepancy.NO_SNAPSHOT, named,
                        counted.snapshot().events()
                                + " events and no snapshot, so a reader is told nothing has"
                                + " happened"));
            }
            return;
        }
        if (!(folded instanceof final Known counted)) {
            findings.add(new Finding(Discrepancy.NO_EVENTS, named, "a snapshot saying "
                    + held.snapshot().kind().spelling() + " over a ledger holding no events"));
            return;
        }
        compare(named, held.snapshot(), counted.snapshot(), findings);
    }

    private static void compare(String named, Snapshot stored, Snapshot folded,
                                List<Finding> findings) {
        if (stored.kind() != folded.kind()) {
            findings.add(new Finding(Discrepancy.KIND_DIFFERS, named, "the snapshot says "
                    + stored.kind().spelling() + " and the newest event says "
                    + folded.kind().spelling()));
        }
        if (!stored.sequence().equals(folded.sequence())) {
            findings.add(new Finding(Discrepancy.SEQUENCE_DIFFERS, named, "the snapshot is at "
                    + stored.sequence() + " and the ledger is at " + folded.sequence()));
        }
        if (stored.events() != folded.events()) {
            findings.add(new Finding(Discrepancy.COUNT_DIFFERS, named, "the snapshot was folded"
                    + " from " + stored.events() + " events and the ledger holds "
                    + folded.events()));
        }
    }

    private static void againstTheRecord(StatePath operation, Materialised folded,
                                         Claimed recordState, List<Finding> findings) {
        final boolean recordEnds = recordState instanceof final Says says
                && says.kind().finality() == JobEventKind.Finality.ENDS;
        final boolean eventEnds = folded instanceof final Known known
                && known.snapshot().kind().finality() == JobEventKind.Finality.ENDS;
        if (recordEnds && !eventEnds) {
            findings.add(new Finding(Discrepancy.RECORD_TERMINAL_WITHOUT_EVENT, operation.path(),
                    "the record says " + ((Says) recordState).kind().spelling() + " and no event"
                            + " says an operation ended"));
        }
        if (eventEnds && !recordEnds) {
            findings.add(new Finding(Discrepancy.EVENT_TERMINAL_WITHOUT_RECORD, operation.path(),
                    "the newest event says " + ((Known) folded).snapshot().kind().spelling()
                            + " and the record has not moved with it"));
        }
    }

    private static Materialised fold(Session session, StatePath operation)
            throws RepositoryException {
        final StatePath ledger = operation.child(EventLedger.NODE);
        final String path = ledger.path();
        if (!session.nodeExists(path)) {
            return Unwritten.NOTHING_HAS_HAPPENED;
        }
        final javax.jcr.NodeIterator children = session.getNode(path).getNodes();
        Materialised newest = Unwritten.NOTHING_HAS_HAPPENED;
        long counted = 0;
        while (children.hasNext()) {
            final Node event = children.nextNode();
            counted = counted + 1;
            newest = newer(newest, event);
        }
        return newest instanceof final Known held
                ? new Known(new Snapshot(held.snapshot().kind(), held.snapshot().sequence(),
                        counted, held.snapshot().updatedAtUnixMilliseconds()))
                : newest;
    }

    private static Materialised newer(Materialised newest, Node event) throws RepositoryException {
        final Optional<JobEventKind> kind =
                JobEventKind.named(event.getProperty(EventLedger.KIND).getString());
        final EventSequence.Outcome sequence =
                EventSequence.of(event.getProperty(EventLedger.SEQUENCE).getLong());
        if (kind.isEmpty() || sequence instanceof EventSequence.Refused) {
            return newest;
        }
        final Snapshot held = new Snapshot(kind.get(), ((EventSequence.Held) sequence).sequence(),
                0, event.getProperty(EventLedger.WRITTEN_AT).getLong());
        return newest instanceof final Known seen
                && seen.snapshot().sequence().compareTo(held.sequence()) >= 0
                ? newest
                : new Known(held);
    }

    /**
     * The findings a verification produced, where it produced any.
     *
     * @param verification what verifying produced
     * @return the findings, which is empty where everything agrees
     */
    public static List<Finding> findingsIn(Verification verification) {
        return verification instanceof final Disagrees disagrees
                ? disagrees.findings()
                : List.of();
    }
}
