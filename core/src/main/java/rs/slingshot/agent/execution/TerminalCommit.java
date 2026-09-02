// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentValue;
import rs.slingshot.agent.store.ArtifactRecord;
import rs.slingshot.agent.store.ArtifactSlot;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.EventSequence;
import rs.slingshot.agent.wire.JobEvent;

/**
 * The one commit that ends an operation: its state, its answer, its last event, and its snapshot.
 *
 * <p>Four writes that are four representations of one fact, so they are one commit. A state saying
 * succeeded beside an answer that was not written is a client fetching something that does not
 * exist; an answer written beside a state that still says running is an answer nobody will ever
 * fetch; and a terminal state with no terminal event is a lookup that says finished to a subscriber
 * who will wait for one forever. Each of those is a separate commit's worth of window, and there is
 * no ordering of separate commits that closes all three.</p>
 *
 * <p>Where the answer is published bytes, this names an artifact the store has already committed
 * and refuses to name one it has not. Nothing here writes an artifact byte: an unreferenced
 * artifact is garbage a sweep collects, while a reference to an artifact that is not there is an
 * answer that is broken forever, so the two failures are not equally bad and the ordering is not a
 * convention.</p>
 */
public final class TerminalCommit {

    /** The property the answer's kind is written in. */
    public static final String RESULT_KIND = "result_kind";

    /** The property an inline answer's own bytes are written in. */
    public static final String RESULT_DOCUMENT = "result_document";

    /** The property a published answer's slot is written in. */
    public static final String RESULT_SLOT = "result_slot";

    /** The property a published answer's byte count is written in. */
    public static final String RESULT_BYTE_COUNT = "result_byte_count";

    /** The property a published answer's digest is written in. */
    public static final String RESULT_DIGEST = "result_digest";

    /** The property the instant the operation ended is written in. */
    public static final String FINISHED_AT = "finished_at_unix_milliseconds";

    private TerminalCommit() {
    }

    /** Why an operation was not ended. */
    public enum Refusal {
        /** Nothing durable holds this operation. */
        NO_RECORD,
        /** The record is not in the state the worker read, so somebody else has moved it. */
        NOT_THE_STATE_THAT_WAS_READ,
        /** The state the worker read does not move to the one it is ending in. */
        NOT_A_PERMITTED_MOVE,
        /** The answer names artifact bytes nothing has committed. */
        ARTIFACT_NOT_COMMITTED,
        /** It already ended, as something else, and an operation ends once. */
        DIFFERENT_OUTCOME,
        /** The ledger would not take the terminal event. */
        EVENT_REFUSED
    }

    /** What ending an operation did. */
    public sealed interface Outcome permits Committed, Unchanged, Refused, AtCapacity {
    }

    /**
     * An operation that has now ended, with everything that says so written together.
     *
     * @param operation the record as it now stands
     * @param event the terminal event, at the sequence it was written at
     */
    public record Committed(LogicalOperation operation, JobEvent event) implements Outcome {
    }

    /**
     * An operation that had already ended as exactly this, so nothing was written a second time.
     *
     * @param operation the record as it stands
     */
    public record Unchanged(LogicalOperation operation) implements Outcome {
    }

    /**
     * An operation that did not end, for a reason about the ending itself.
     *
     * @param refusal why not
     * @param detail what was observed, naming both sides where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * An operation that did not end because the store has no room for what says so.
     *
     * @param refusal what the capacity authority said
     */
    public record AtCapacity(CapacityLedger.Refused refusal) implements Outcome {
    }

    /**
     * Ends one operation, writing everything that says so in one commit or nothing at all.
     *
     * @param session the session to write under
     * @param caller whose share the event and the answer come out of
     * @param read the record as the worker read it, which is what the state is advanced from
     * @param outcome what the execution ended as
     * @param contract the authenticated contract, which declares every bound
     * @return what ending it did
     * @throws RepositoryException if the repository fails
     */
    public static Outcome commit(Session session, StatePath.Caller caller, LogicalOperation read,
                                 ExecutionOutcome outcome, AgentContract contract)
            throws RepositoryException {
        final OperationStore.Outcome current = OperationStore.read(session, read.identity());
        if (current instanceof OperationStore.Refused) {
            return new Refused(Refusal.NO_RECORD, "nothing durable holds "
                    + read.identity().identifier());
        }
        final LogicalOperation stored = ((OperationStore.Held) current).operation();
        if (stored.state().finality() == rs.slingshot.agent.wire.JobEventKind.Finality.ENDS) {
            return alreadyEnded(session, stored, outcome);
        }
        if (stored.state() != read.state()) {
            return new Refused(Refusal.NOT_THE_STATE_THAT_WAS_READ, "the record is "
                    + stored.state().spelling() + " and this worker read "
                    + read.state().spelling() + ", so somebody else has moved it");
        }
        if (!read.state().permits(outcome.state())) {
            return new Refused(Refusal.NOT_A_PERMITTED_MOVE, read.state().spelling()
                    + " does not move to " + outcome.state().spelling());
        }
        return referenced(session, caller, stored, outcome, contract);
    }

    private static Outcome alreadyEnded(Session session, LogicalOperation stored,
                                        ExecutionOutcome outcome) throws RepositoryException {
        final Node record = session.getNode(OperationStore.pathOf(stored.identity()).path());
        final String written = record.hasProperty(RESULT_KIND)
                ? record.getProperty(RESULT_KIND).getString()
                : "";
        if (stored.state() == outcome.state() && written.equals(outcome.kind().spelling())
                && sameAnswer(record, outcome)) {
            return new Unchanged(stored);
        }
        return new Refused(Refusal.DIFFERENT_OUTCOME, "this operation ended as "
                + stored.state().spelling() + " with a " + written + " answer, and this commit says "
                + outcome.state().spelling() + " with a " + outcome.kind().spelling() + " one");
    }

    private static boolean sameAnswer(Node record, ExecutionOutcome outcome)
            throws RepositoryException {
        if (outcome.result() instanceof final ExecutionOutcome.Inline inline) {
            return record.hasProperty(RESULT_DOCUMENT)
                    && record.getProperty(RESULT_DOCUMENT).getString().equals(inline.document());
        }
        if (outcome.result() instanceof final ExecutionOutcome.Published published) {
            return record.hasProperty(RESULT_DIGEST)
                    && record.getProperty(RESULT_DIGEST).getString()
                            .equals(published.digest().rendered());
        }
        return true;
    }

    private static Outcome referenced(Session session, StatePath.Caller caller,
                                      LogicalOperation stored, ExecutionOutcome outcome,
                                      AgentContract contract) throws RepositoryException {
        if (outcome.result() instanceof final ExecutionOutcome.Published published) {
            final Optional<ArtifactRecord> committed = ArtifactStore.read(session,
                    OperationStore.pathOf(stored.identity()), published.slot());
            if (committed.isEmpty()) {
                return new Refused(Refusal.ARTIFACT_NOT_COMMITTED, "nothing is committed in slot "
                        + published.slot().name() + ", and a reference to an artifact that is not"
                        + " there is an answer that is broken forever");
            }
            final Optional<Refused> mismatch = mismatch(published, committed.get());
            if (mismatch.isPresent()) {
                return mismatch.get();
            }
        }
        return written(session, caller, stored, outcome, contract);
    }

    private static Optional<Refused> mismatch(ExecutionOutcome.Published published,
                                              ArtifactRecord committed) {
        if (committed.byteCount() != published.byteCount()
                || !committed.digest().rendered().equals(published.digest().rendered())) {
            return Optional.of(new Refused(Refusal.ARTIFACT_NOT_COMMITTED, "slot "
                    + published.slot().name() + " holds " + committed.byteCount()
                    + " bytes digesting to " + committed.digest() + ", and this answer names "
                    + published.byteCount() + " bytes digesting to " + published.digest()));
        }
        return Optional.empty();
    }

    private static Outcome written(Session session, StatePath.Caller caller,
                                   LogicalOperation stored, ExecutionOutcome outcome,
                                   AgentContract contract) throws RepositoryException {
        final StatePath operation = OperationStore.pathOf(stored.identity());
        final long next = EventLedger.events(session, operation.child(EventLedger.NODE));
        final Optional<JobEvent> terminal = terminalEvent(stored, outcome, next, contract);
        if (terminal.isEmpty()) {
            return new Refused(Refusal.EVENT_REFUSED,
                    "the terminal event this build would write is not one it reads back");
        }
        final CanonicalByteWriter.Outcome canonical =
                CanonicalByteWriter.write(document(stored, outcome, next));
        if (canonical instanceof CanonicalByteWriter.Refused) {
            return new Refused(Refusal.EVENT_REFUSED,
                    "the terminal event has no canonical form to write down");
        }
        return appended(session, caller, stored, outcome, new Terminal(terminal.get(),
                ((CanonicalByteWriter.Written) canonical).bytes(), contract));
    }

    /**
     * The terminal event as it will be written, with the contract it is written under.
     *
     * @param event the event
     * @param canonical its canonical bytes
     * @param contract the authenticated contract
     */
    private record Terminal(JobEvent event, byte[] canonical, AgentContract contract) {
    }

    private static Outcome appended(Session session, StatePath.Caller caller,
                                    LogicalOperation stored, ExecutionOutcome outcome,
                                    Terminal terminal) throws RepositoryException {
        final EventLedger.Outcome appended;
        try {
            appended = SnapshotStore.record(session, caller, terminal.event(),
                    terminal.canonical(), outcome.finishedAtUnixMilliseconds(),
                    terminal.contract(), (written, event) -> end(written, stored, outcome));
        } catch (final IllegalStateException interrupted) {
            // The record moved while this commit was being prepared. Nothing was written: the
            // event, the answer, the state, and the snapshot are one commit, and it did not happen.
            if (!(interrupted.getCause() instanceof javax.jcr.InvalidItemStateException)) {
                throw interrupted;
            }
            session.refresh(false);
            return new Refused(Refusal.NOT_THE_STATE_THAT_WAS_READ,
                    interrupted.getCause().getMessage());
        }
        if (appended instanceof final EventLedger.AtCapacity full) {
            return new AtCapacity(full.refusal());
        }
        if (!(appended instanceof EventLedger.Appended)) {
            return new Refused(Refusal.EVENT_REFUSED,
                    "the ledger would not take the terminal event: " + appended);
        }
        return new Committed(OperationStore.read(session, stored.identity())
                instanceof final OperationStore.Held held ? held.operation() : stored,
                terminal.event());
    }

    private static void end(Session session, LogicalOperation stored, ExecutionOutcome outcome)
            throws RepositoryException {
        final Node record = session.getNode(OperationStore.pathOf(stored.identity()).path());
        if (!record.getProperty(OperationStore.STATE).getString()
                .equals(stored.state().spelling())) {
            throw new javax.jcr.InvalidItemStateException("the record moved to "
                    + record.getProperty(OperationStore.STATE).getString()
                    + " while this commit was being made");
        }
        record.setProperty(OperationStore.STATE, outcome.state().spelling());
        record.setProperty(RESULT_KIND, outcome.kind().spelling());
        record.setProperty(FINISHED_AT, outcome.finishedAtUnixMilliseconds());
        answer(record, outcome);
    }

    private static void answer(Node record, ExecutionOutcome outcome) throws RepositoryException {
        if (outcome.result() instanceof final ExecutionOutcome.Inline inline) {
            record.setProperty(RESULT_DOCUMENT, inline.document());
        }
        if (outcome.result() instanceof final ExecutionOutcome.Published published) {
            record.setProperty(RESULT_SLOT, published.slot().name());
            record.setProperty(RESULT_BYTE_COUNT, published.byteCount());
            record.setProperty(RESULT_DIGEST, published.digest().rendered());
        }
    }

    private static Optional<JobEvent> terminalEvent(LogicalOperation stored,
                                                    ExecutionOutcome outcome, long sequence,
                                                    AgentContract contract) {
        final JobEvent.Outcome read = JobEvent.read(document(stored, outcome, sequence),
                stored.identity().generation(), contract);
        return read instanceof final JobEvent.Held held
                ? Optional.of(held.event())
                : Optional.empty();
    }

    private static DocumentValue document(LogicalOperation stored, ExecutionOutcome outcome,
                                          long sequence) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(JobEvent.GENERATION,
                new DocumentValue.Whole(stored.identity().generation().number()));
        members.put(JobEvent.IDENTIFIER,
                new DocumentValue.Text(stored.identity().identifier().rendered()));
        members.put(JobEvent.KIND, new DocumentValue.Text(outcome.state().kind().spelling()));
        members.put(JobEvent.SEQUENCE, new DocumentValue.Whole(sequence));
        return new DocumentValue.Mapping(members);
    }

    /**
     * What one operation's answer is, where it has ended at all.
     *
     * @param session the session to read under
     * @param operation the operation's own record
     * @return the answer, or nothing where the operation has not ended
     * @throws RepositoryException if the repository fails
     */
    public static Optional<ExecutionOutcome.Result> answerIn(Session session, StatePath operation)
            throws RepositoryException {
        if (!session.nodeExists(operation.path())) {
            return Optional.empty();
        }
        final Node record = session.getNode(operation.path());
        if (!record.hasProperty(RESULT_KIND)) {
            return Optional.empty();
        }
        return ExecutionOutcome.Kind.named(record.getProperty(RESULT_KIND).getString())
                .map(kind -> answerOf(record, kind));
    }

    private static ExecutionOutcome.Result answerOf(Node record, ExecutionOutcome.Kind kind) {
        try {
            if (kind == ExecutionOutcome.Kind.INLINE) {
                return new ExecutionOutcome.Inline(
                        record.getProperty(RESULT_DOCUMENT).getString());
            }
            if (kind == ExecutionOutcome.Kind.PUBLISHED) {
                return published(record);
            }
            return ExecutionOutcome.Nothing.NOTHING_TO_RETURN;
        } catch (final RepositoryException unreadable) {
            throw new IllegalStateException("the answer this record holds could not be read",
                    unreadable);
        }
    }

    private static ExecutionOutcome.Result published(Node record) throws RepositoryException {
        final ArtifactSlot.Outcome slot =
                ArtifactSlot.of(record.getProperty(RESULT_SLOT).getString());
        final DigestValue.Outcome digest =
                DigestValue.of(record.getProperty(RESULT_DIGEST).getString());
        return slot instanceof final ArtifactSlot.Held held
                && digest instanceof final DigestValue.Held known
                ? new ExecutionOutcome.Published(held.slot(),
                        record.getProperty(RESULT_BYTE_COUNT).getLong(), known.digest())
                : ExecutionOutcome.Nothing.NOTHING_TO_RETURN;
    }

    /**
     * The one reason an operation did not end, where that is why.
     *
     * @param outcome what ending it did
     * @return the refusal, or nothing where it ended or the store had no room
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }

    /**
     * The sequence a terminal event would be written at.
     *
     * @param session the session to read under
     * @param operation the operation's own record
     * @return the sequence
     * @throws RepositoryException if the repository fails
     */
    public static Optional<EventSequence> nextSequence(Session session, StatePath operation)
            throws RepositoryException {
        final EventSequence.Outcome next =
                EventSequence.of(EventLedger.events(session, operation.child(EventLedger.NODE)));
        return next instanceof final EventSequence.Held held
                ? Optional.of(held.sequence())
                : Optional.empty();
    }
}
