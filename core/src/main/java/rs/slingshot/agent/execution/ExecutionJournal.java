// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import static rs.slingshot.agent.store.EventLedger.TERMINAL_BUDGET;

import java.util.List;
import java.util.Optional;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactRecord;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.ClaimByCreation;
import rs.slingshot.agent.store.CompareAndSet;
import rs.slingshot.agent.store.SnapshotStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/** Durable completion evidence that a retry can publish without invoking the command again. */
public final class ExecutionJournal {
    /** The operation child holding completion evidence and its retained capacity. */
    public static final String NODE = rs.slingshot.agent.store.MaintenanceSweep.COMPLETION;
    /** The server instant at which execution acquired its durable start. */
    public static final String STARTED_AT = "execution_started_at_unix_milliseconds";
    private static final String PHASE = "completion_phase";
    private static final String EXECUTING = "executing";
    private static final String READY = "ready";

    private ExecutionJournal() {
    }

    /** Records uncertainty on scope exit while preserving the handler's primary exception. */
    public static final class Attempt implements AutoCloseable {
        private final Session session;
        private final StatePath operation;
        private final AgentContract contract;

        private Attempt(Session session, StatePath operation, AgentContract contract) {
            this.session = session;
            this.operation = operation;
            this.contract = contract;
        }

        /**
         * Validates and records the handler's first completion.
         *
         * @param completion the handler declaration
         * @throws RepositoryException if result evidence or its publication fails
         */
        public void complete(ExecutionOutcome.Completion completion) throws RepositoryException {
            completed(session, operation, declared(session, operation, completion,
                    System.currentTimeMillis(), contract), contract);
        }

        /**
         * Records explicit uncertainty if no completion was durably selected.
         *
         * @throws RepositoryException if the fallback cannot be persisted
         */
        @Override
        public void close() throws RepositoryException {
            final ExecutionOutcome outcome = ((ExecutionOutcome.Held) ExecutionOutcome.of(
                    ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED, System.currentTimeMillis(), contract))
                    .outcome();
            completed(session, operation, outcome, contract);
        }
    }

    /**
     * Borrows a state session for the lexical lifetime of one started handler attempt.
     *
     * @param session the request's state session
     * @param operation the operation with durable execution-start evidence
     * @param contract the authenticated bounds already checked before starting
     * @return the scope that preserves uncertainty and suppressed cleanup failures
     */
    public static Attempt attempt(Session session, StatePath operation, AgentContract contract) {
        return new Attempt(session, operation, contract);
    }

    /**
     * Reserves terminal-event capacity and publishes it atomically with execution-start evidence.
     *
     * @param session the state session
     * @param operation the accepted operation
     * @param nowUnixMilliseconds the server start instant
     * @param contract the authenticated capacity and result bounds
     * @return the running operation, or absence when capacity or the start transition was refused
     * @throws RepositoryException if allocation or start persistence fails
     */
    public static Optional<LogicalOperation> start(Session session, LogicalOperation operation,
                                                   long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        if (ExecutionOutcome.of(ExecutionOutcome.Uncertain.EFFECTS_UNDETERMINED, nowUnixMilliseconds,
                contract) instanceof ExecutionOutcome.Refused) {
            return Optional.empty();
        }
        final CapacityLedger.ReservationAdmission room = CapacityLedger.take(session, operation.caller(),
                List.of(new CapacityReservation.Charge(AccountedQuantity.EVENT_ROWS, 1),
                        new CapacityReservation.Charge(AccountedQuantity.EVENT_BYTES,
                                TerminalCommit.maximumEventBytes(operation))), contract);
        if (!(room instanceof final CapacityLedger.Reserved reserved)) {
            return Optional.empty();
        }
        try (CapacityReservation.Guard guard = reserved.reservation().guard(session, contract)) {
            return withCompletionRoom(session, operation, nowUnixMilliseconds, contract, guard.reservation());
        }
    }

    private static Optional<LogicalOperation> withCompletionRoom(Session session, LogicalOperation operation,
                                                                  long now, AgentContract contract,
                                                                  CapacityReservation event)
            throws RepositoryException {
        final long snapshot = java.util.Arrays.stream(OperationState.values())
                .filter(state -> state.finality() == rs.slingshot.agent.wire.JobEventKind.Finality.ENDS)
                .mapToLong(state -> SnapshotStore.bytesFor(state.kind())).max().orElseThrow();
        final CapacityLedger.ReservationAdmission room = CapacityLedger.take(session, operation.caller(),
                footprint(contract.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES),
                        snapshot), contract);
        if (!(room instanceof final CapacityLedger.Reserved reserved)) {
            return Optional.empty();
        }
        try (CapacityReservation.Guard guard = reserved.reservation().guard(session, contract)) {
            final OperationStore.Outcome started = OperationStore.start(session, operation, node -> {
                CapacityReservation.retain(session, guard.reservation(), stageStart(node, now));
                CapacityReservation.retain(session, event, node.addNode(TERMINAL_BUDGET, "nt:unstructured"));
            });
            return started instanceof final OperationStore.Held held
                    ? Optional.of(held.operation()) : Optional.empty();
        }
    }

    private static List<CapacityReservation.Charge> footprint(long resultBytes, long snapshotBytes) {
        return List.of(new CapacityReservation.Charge(AccountedQuantity.RESULT_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.RESULT_BYTES, resultBytes),
                new CapacityReservation.Charge(AccountedQuantity.SNAPSHOT_ROWS, 1),
                new CapacityReservation.Charge(AccountedQuantity.SNAPSHOT_BYTES, snapshotBytes));
    }

    /**
     * Stages the initial evidence in the same transaction that starts the operation.
     *
     * @param operation the operation whose start is pending
     * @param nowUnixMilliseconds the server execution-start instant
     * @return the journal node to which completion capacity can be attached
     * @throws RepositoryException if initial evidence cannot be staged
     */
    static Node stageStart(Node operation, long nowUnixMilliseconds) throws RepositoryException {
        final Node journal = operation.addNode(NODE, "nt:unstructured");
        journal.setProperty(PHASE, EXECUTING);
        journal.setProperty(STARTED_AT, nowUnixMilliseconds);
        return journal;
    }

    /**
     * Validates a handler completion and makes an unavailable result explicit.
     *
     * @param session the state session holding any published artifact
     * @param operation the operation's path
     * @param completion the handler's declaration
     * @param finishedAt the completion instant
     * @param contract the authenticated result bounds
     * @return the validated completion or a bounded statement of result uncertainty
     * @throws RepositoryException if published artifact evidence cannot be read
     */
    public static ExecutionOutcome declared(Session session, StatePath operation,
                                             ExecutionOutcome.Completion completion, long finishedAt,
                                             AgentContract contract) throws RepositoryException {
        final ExecutionOutcome.Outcome read = ExecutionOutcome.of(completion, finishedAt, contract);
        if (read instanceof final ExecutionOutcome.Held held && available(session, operation,
                held.outcome())) {
            return held.outcome();
        }
        return ((ExecutionOutcome.Held) ExecutionOutcome.of(ExecutionOutcome.Uncertain.RESULT_UNAVAILABLE,
                finishedAt, contract)).outcome();
    }

    private static boolean available(Session session, StatePath operation, ExecutionOutcome outcome)
            throws RepositoryException {
        if (!(outcome.result() instanceof final ExecutionOutcome.Published published)) {
            return true;
        }
        final Optional<ArtifactRecord> artifact = ArtifactStore.read(session, operation, published.slot());
        return artifact.filter(record -> record.byteCount() == published.byteCount()
                && record.digest().rendered().equals(published.digest().rendered())).isPresent();
    }

    /**
     * Records the first declared completion, or preserves the completion already selected by recovery.
     *
     * @param session the state session
     * @param operation the running operation's path
     * @param outcome the validated bounded completion
     * @param contract the authenticated capacity and result bounds
     * @return whether this writer recorded the completion, lost contention, or found prior evidence
     * @throws RepositoryException if persistence fails
     */
    public static WriteOutcome completed(Session session, StatePath operation, ExecutionOutcome outcome,
                                          AgentContract contract) throws RepositoryException {
        session.refresh(false);
        if (!session.nodeExists(operation.child(NODE).path())) {
            return WriteOutcome.VALUE_CHANGED;
        }
        final Node journal = session.getNode(operation.child(NODE).path());
        if (!EXECUTING.equals(journal.getProperty(PHASE).getString())) {
            return WriteOutcome.VALUE_CHANGED;
        }
        final Optional<CapacityReservation> reserved = CapacityReservation.ofResource(session, journal);
        if (reserved.isEmpty() && journal.hasProperty(CapacityReservation.RESOURCE_RESERVATION)) {
            throw new RepositoryException("the completion's retained capacity identity is missing");
        }
        if (reserved.isEmpty()) {
            return selected(session, operation, outcome, node -> { });
        }
        try (CapacityReservation.Batch batch = CapacityReservation.batch(session, contract)) {
            final CapacityReservation replacement = batch.create(reserved.get().caller(),
                    footprint(outcome.inlineBytes(), SnapshotStore.bytesFor(outcome.state().kind())))
                    .orElseThrow(() -> new RepositoryException("completion capacity allocation contended"));
            return selected(session, operation, outcome, node -> {
                if (!(CapacityLedger.transfer(session, node, node, replacement, contract)
                        instanceof CapacityLedger.Admitted)) {
                    throw new RepositoryException("reserved completion capacity could not be retained");
                }
            });
        }
    }

    private static WriteOutcome selected(Session session, StatePath operation, ExecutionOutcome outcome,
                                           ClaimByCreation.InitialValues alongside)
            throws RepositoryException {
        try {
            return stageCompletion(session, operation, outcome, alongside);
        } finally {
            session.refresh(false);
        }
    }

    private static WriteOutcome stageCompletion(Session session, StatePath operation,
                                                 ExecutionOutcome outcome,
                                                 ClaimByCreation.InitialValues alongside)
            throws RepositoryException {
        if (!session.nodeExists(operation.child(NODE).path())) {
            return WriteOutcome.VALUE_CHANGED;
        }
        final Node record = session.getNode(operation.path());
        CompareAndSet.stamp(record);
        final Node journal = record.getNode(NODE);
        if (!OperationState.RUNNING.spelling().equals(record.getProperty(OperationStore.STATE).getString())
                || !EXECUTING.equals(journal.getProperty(PHASE).getString())) {
            return WriteOutcome.VALUE_CHANGED;
        }
        alongside.write(journal);
        journal.setProperty(PHASE, READY);
        journal.setProperty(OperationStore.STATE, outcome.state().spelling());
        journal.setProperty(TerminalCommit.RESULT_KIND, outcome.kind().spelling());
        journal.setProperty(TerminalCommit.FINISHED_AT, outcome.finishedAtUnixMilliseconds());
        TerminalCommit.stageAnswer(journal, outcome);
        try {
            session.save();
            return WriteOutcome.WRITTEN;
        } catch (final InvalidItemStateException contended) {
            return WriteOutcome.CONTENDED;
        }
    }

    /**
     * Retires pending evidence only with the terminal result selected by the journal.
     *
     * @param session the enclosing terminal transaction
     * @param operation the operation being ended
     * @param outcome the selected terminal result
     * @param contract the authenticated result bounds
     * @throws RepositoryException if evidence differs or its retirement cannot be staged
     */
    static void stageEnd(Session session, StatePath operation, ExecutionOutcome outcome,
            AgentContract contract)
            throws RepositoryException {
        if (!session.nodeExists(operation.child(NODE).path())) {
            return;
        }
        if (!pending(session, operation, contract).filter(outcome::equals).isPresent()) {
            throw new InvalidItemStateException("the terminal outcome differs from the selected completion");
        }
        final Node journal = session.getNode(operation.child(NODE).path());
        journal.setProperty(PHASE, "committed");
        strip(journal, TerminalCommit.RESULT_KIND);
        strip(journal, TerminalCommit.RESULT_DOCUMENT);
        strip(journal, TerminalCommit.RESULT_SLOT);
        strip(journal, TerminalCommit.RESULT_BYTE_COUNT);
        strip(journal, TerminalCommit.RESULT_DIGEST);
    }

    private static void strip(Node journal, String property) throws RepositoryException {
        if (journal.hasProperty(property)) {
            journal.getProperty(property).remove();
        }
    }

    /**
     * Reads bounded completion evidence without running or authorizing any effect.
     *
     * @param session the state session
     * @param operation the operation's path
     * @param contract the authenticated result bounds
     * @return the recorded outcome, or absence while the handler has not declared one
     * @throws RepositoryException if the evidence cannot be read
     */
    public static Optional<ExecutionOutcome> pending(Session session, StatePath operation,
            AgentContract contract)
            throws RepositoryException {
        final StatePath path = operation.child(NODE);
        if (!session.nodeExists(path.path())) {
            return Optional.empty();
        }
        final Node journal = session.getNode(path.path());
        if (!READY.equals(journal.getProperty(PHASE).getString())) {
            return Optional.empty();
        }
        final Optional<OperationState> state = OperationState.named(
                journal.getProperty(OperationStore.STATE).getString());
        final Optional<ExecutionOutcome.Result> result = TerminalCommit.answerIn(session, path);
        if (state.isEmpty() || result.isEmpty()) {
            return Optional.empty();
        }
        final ExecutionOutcome.Outcome outcome = ExecutionOutcome.of(state.get(), result.get(),
                journal.getProperty(TerminalCommit.FINISHED_AT).getLong(), contract);
        return outcome instanceof final ExecutionOutcome.Held held
                ? Optional.of(held.outcome()) : Optional.empty();
    }
}
