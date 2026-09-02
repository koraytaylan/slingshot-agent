// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.wire.EventSequence;
import rs.slingshot.agent.wire.JobEvent;

/**
 * The append-only record of what happened to one operation, sequenced and bounded twice over.
 *
 * <p>An event is written at a path its own sequence derives, so appending is a claim: two writers
 * that both believe they are writing sequence seven produce one event and one refusal rather than
 * two events a reader has to choose between. A gap and a repeat are two different refusals on
 * purpose — a gap means an event was lost, a repeat means a writer replayed, and a caller told the
 * wrong one of those would look in the wrong place.</p>
 *
 * <p>Bounded twice, because one runaway command must not fill the store and the store as a whole
 * has a size somebody chose. The per-operation bounds are {@link JobEvent.Budget}'s, which reads
 * them from the contract; the per-generation and per-caller ones are the capacity authority's,
 * reached through {@link LedgerAdmission}. Neither bound is written down here, and no count is
 * kept here: what this ledger holds is read from the tree it wrote.</p>
 */
public final class EventLedger {

    /** The child of an operation the ledger lives under. */
    public static final String NODE = "events";

    /** The property an event's kind is written in. */
    public static final String KIND = "kind";

    /** The property an event's sequence is written in. */
    public static final String SEQUENCE = "sequence";

    /** The property an event's own size is written in. */
    public static final String BYTES = "byte_count";

    /** The property the instant an event was appended is written in. */
    public static final String WRITTEN_AT = "written_at_unix_milliseconds";

    /** The property the event's canonical bytes are written in, so a replay repeats them exactly. */
    public static final String DOCUMENT = "document";

    /** How wide a sequence's name is, so the order names sort in is the order they happened in. */
    private static final int NAME_DIGITS = 12;

    private EventLedger() {
    }

    /**
     * What else is written in the one commit that writes an event.
     *
     * <p>The seam exists so that a fact which must not disagree with the ledger is written with it
     * rather than after it. A second commit is a window, and a window is where a reader sees a
     * snapshot that says one thing and a ledger that says another.</p>
     */
    @FunctionalInterface
    public interface Alongside {

        /**
         * Writes whatever else belongs in this event's commit.
         *
         * @param session the session the event is being written under, before it is saved
         * @param event the event being written
         * @throws RepositoryException if the repository fails
         */
        void write(Session session, JobEvent event) throws RepositoryException;
    }

    /** Why an event was not appended, where the store had room for it. */
    public enum Refusal {
        /** There is no operation to append to, so an event would be a fact about nothing. */
        NO_OPERATION,
        /** It is further along than the next sequence, which means an event was lost. */
        SEQUENCE_GAP,
        /** That sequence is already there, which means a writer replayed. */
        SEQUENCE_REPEAT,
        /** This operation holds every event it may hold. */
        TOO_MANY_EVENTS,
        /** This operation's events already come to every byte they may come to. */
        TOO_MANY_EVENT_BYTES
    }

    /** What appending did: the event, no room, a refusal, or a write that did not happen. */
    public sealed interface Outcome permits Appended, Refused, AtCapacity, NotWritten {
    }

    /**
     * An event this ledger now holds.
     *
     * @param event the event
     * @param events how many events the operation now holds
     * @param bytes how many bytes those events now come to
     */
    public record Appended(JobEvent event, long events, long bytes) implements Outcome {
    }

    /**
     * An event that was not appended for a reason about the event itself.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * No room in the store, which is a store that needs a sweep rather than an investigation.
     *
     * @param refusal what the capacity authority said, with the count and the bound it reached
     */
    public record AtCapacity(CapacityLedger.Refused refusal) implements Outcome {
    }

    /**
     * The write did not happen, which is an investigation rather than a sweep.
     *
     * @param outcome what the store did instead of writing
     * @param detail what was observed
     */
    public record NotWritten(WriteOutcome outcome, String detail) implements Outcome {
    }

    /**
     * Appends one event at the sequence it names, or does nothing at all.
     *
     * @param session the session to write under
     * @param caller whose share the room comes out of
     * @param event the event, which names the operation and the sequence it belongs at
     * @param canonical the event's canonical bytes, which are what a replay repeats
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares all four bounds
     * @return what appending it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome append(Session session, StatePath.Caller caller, JobEvent event,
                                 byte[] canonical, long nowUnixMilliseconds,
                                 AgentContract contract) throws RepositoryException {
        return append(session, caller, event, canonical, nowUnixMilliseconds, contract,
                (written, appended) -> { });
    }

    /**
     * Appends one event, and writes whatever else belongs in the same commit.
     *
     * @param session the session to write under
     * @param caller whose share the room comes out of
     * @param event the event, which names the operation and the sequence it belongs at
     * @param canonical the event's canonical bytes, which are what a replay repeats
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares all four bounds
     * @param alongside what else the commit that writes this event writes
     * @return what appending it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome append(Session session, StatePath.Caller caller, JobEvent event,
                                 byte[] canonical, long nowUnixMilliseconds,
                                 AgentContract contract, Alongside alongside)
            throws RepositoryException {
        final StatePath operation = StatePath.operation(event.generation(), event.identifier());
        if (!session.nodeExists(operation.path())) {
            return new Refused(Refusal.NO_OPERATION, "there is no operation at " + operation.path()
                    + " for an event to be about");
        }
        ClaimByCreation.claim(session, operation.child(NODE), "nt:unstructured", node -> { });
        return sequenced(session, caller, new Append(event, canonical.clone(), alongside),
                nowUnixMilliseconds, contract, operation.child(NODE));
    }

    /**
     * One append as its caller asked for it.
     *
     * <p>Carried as a record rather than as three arguments because the three travel together the
     * whole way down: the event, the bytes a replay repeats, and what else its commit writes.</p>
     *
     * @param event the event
     * @param canonical its canonical bytes
     * @param alongside what else the commit writes
     */
    private record Append(JobEvent event, byte[] canonical, Alongside alongside) {
    }

    private static Outcome sequenced(Session session, StatePath.Caller caller, Append append,
                                     long nowUnixMilliseconds, AgentContract contract,
                                     StatePath ledger) throws RepositoryException {
        final JobEvent event = append.event();
        final byte[] canonical = append.canonical();
        final long held = events(session, ledger);
        final long asked = event.sequence().number();
        if (asked < held) {
            return new Refused(Refusal.SEQUENCE_REPEAT, "sequence " + asked + " is already there,"
                    + " and the next one is " + held);
        }
        if (asked > held) {
            return new Refused(Refusal.SEQUENCE_GAP, "sequence " + asked + " leaves " + held
                    + " unwritten, and an event nothing holds is an event that was lost");
        }
        final Optional<JobEvent.Refused> budget = JobEvent.Budget.from(contract)
                .admits(held, bytes(session, ledger), canonical.length);
        if (budget.isPresent()) {
            return new Refused(refusalOf(budget.get()), budget.get().detail());
        }
        return admitted(session, caller, append, nowUnixMilliseconds, contract, ledger);
    }

    private static Outcome admitted(Session session, StatePath.Caller caller, Append append,
                                    long nowUnixMilliseconds, AgentContract contract,
                                    StatePath ledger) throws RepositoryException {
        final LedgerAdmission.Outcome admission =
                LedgerAdmission.admit(session, caller, append.canonical().length, contract);
        if (admission instanceof final LedgerAdmission.Refused refused) {
            return new AtCapacity(refused.refusal());
        }
        if (admission instanceof final LedgerAdmission.NotCounted notCounted) {
            return new NotWritten(notCounted.notCounted().outcome(), "the counters an event is"
                    + " admitted against did not move, so nothing was written against them");
        }
        return written(session, caller, append, nowUnixMilliseconds, contract, ledger);
    }

    private static Outcome written(Session session, StatePath.Caller caller, Append append,
                                   long nowUnixMilliseconds, AgentContract contract,
                                   StatePath ledger) throws RepositoryException {
        final StatePath path = ledger.child(nameOf(append.event().sequence()));
        final WriteOutcome claimed = ClaimByCreation.claim(session, path, "nt:unstructured",
                node -> fill(node, append, nowUnixMilliseconds));
        if (claimed != WriteOutcome.CLAIMED) {
            LedgerAdmission.release(session, caller, append.canonical().length, contract);
            return new Refused(Refusal.SEQUENCE_REPEAT, "another writer took sequence "
                    + append.event().sequence().number() + " first, and one sequence is one event");
        }
        return new Appended(append.event(), events(session, ledger), bytes(session, ledger));
    }

    private static void fill(Node node, Append append, long nowUnixMilliseconds) {
        try {
            node.setProperty(KIND, append.event().kind().spelling());
            node.setProperty(SEQUENCE, append.event().sequence().number());
            node.setProperty(BYTES, append.canonical().length);
            node.setProperty(WRITTEN_AT, nowUnixMilliseconds);
            node.setProperty(DOCUMENT,
                    new String(append.canonical(), StandardCharsets.UTF_8));
            append.alongside().write(node.getSession(), append.event());
        } catch (final RepositoryException failed) {
            throw new IllegalStateException("an event node this writer created would not hold what"
                    + " an event is, or what belongs in its commit could not be written", failed);
        }
    }

    /**
     * How many events one operation holds, read from the tree rather than from a count.
     *
     * @param session the session to read under
     * @param ledger the operation's ledger
     * @return the number of events
     * @throws RepositoryException if the repository fails
     */
    public static long events(Session session, StatePath ledger) throws RepositoryException {
        return session.nodeExists(ledger.path()) ? session.getNode(ledger.path()).getNodes()
                .getSize() : 0;
    }

    /**
     * How many bytes one operation's events come to, read from the tree rather than from a count.
     *
     * @param session the session to read under
     * @param ledger the operation's ledger
     * @return the number of bytes
     * @throws RepositoryException if the repository fails
     */
    public static long bytes(Session session, StatePath ledger) throws RepositoryException {
        if (!session.nodeExists(ledger.path())) {
            return 0;
        }
        final NodeIterator children = session.getNode(ledger.path()).getNodes();
        long counted = 0;
        while (children.hasNext()) {
            counted = counted + children.nextNode().getProperty(BYTES).getLong();
        }
        return counted;
    }

    /**
     * Every event one operation holds, in the order they happened.
     *
     * @param session the session to read under
     * @param ledger the operation's ledger
     * @return the canonical bytes of each event, oldest first
     * @throws RepositoryException if the repository fails
     */
    public static List<String> held(Session session, StatePath ledger) throws RepositoryException {
        if (!session.nodeExists(ledger.path())) {
            return List.of();
        }
        final java.util.SortedMap<String, String> byName = new java.util.TreeMap<>();
        final NodeIterator children = session.getNode(ledger.path()).getNodes();
        while (children.hasNext()) {
            final Node event = children.nextNode();
            byName.put(event.getName(), event.getProperty(DOCUMENT).getString());
        }
        return List.copyOf(byName.values());
    }

    /**
     * Where one operation's ledger lives.
     *
     * @param event an event about that operation
     * @return the ledger's path
     */
    public static StatePath pathOf(JobEvent event) {
        return StatePath.operation(event.generation(), event.identifier()).child(NODE);
    }

    /**
     * How one sequence is named, which is wide enough that names sort the way sequences do.
     *
     * @param sequence the sequence
     * @return the name
     */
    public static String nameOf(EventSequence sequence) {
        return String.format("%0" + NAME_DIGITS + "d", sequence.number());
    }

    /**
     * The one reason an event was not appended, where that is why.
     *
     * @param outcome what appending it did
     * @return the refusal, or nothing where it was appended or the store had no room
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }

    private static Refusal refusalOf(JobEvent.Refused refused) {
        return refused.refusal() == JobEvent.Refusal.TOO_MANY_EVENTS
                ? Refusal.TOO_MANY_EVENTS
                : Refusal.TOO_MANY_EVENT_BYTES;
    }
}
