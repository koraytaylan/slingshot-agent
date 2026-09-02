// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.ArrayList;
import java.util.List;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.ArtifactStore;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.EventLedger;
import rs.slingshot.agent.store.MaintenanceSweep;
import rs.slingshot.agent.store.RetentionPolicy;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * What the store says about unfinished work, at startup and on the contract's own interval.
 *
 * <p>On startup the store is the only witness. The job system may believe anything — a queue
 * survives a restart, and what it holds is a delivery rather than a fact — so what happened is what
 * is written down. Nothing here invents a durable thing: no operation is created, no identifier is
 * derived, no attempt is recorded, and no command is run under anybody's identity, because there is
 * no identity here to run one under.</p>
 *
 * <p>It runs on an interval as well as at startup, because an author instance that is never
 * restarted is one where startup-only recovery never comes, and an operation left undetermined for
 * a week is a client waiting for a week.</p>
 */
public final class RestartRecovery {

    /** The child of an operation the slots an inbound manifest declared live under. */
    public static final String INTAKE = MaintenanceSweep.INTAKE;

    /** The property one declared slot's own size is written in. */
    public static final String DECLARED_BYTES = "declared_byte_count";

    private RestartRecovery() {
    }

    /**
     * What one operation was found to be.
     *
     * @param operation where the record is
     * @param disposition what reconciliation decided about it
     * @param detail what was observed
     */
    public record Finding(StatePath operation, RecoveryDisposition disposition, String detail) {
    }

    /**
     * What one reconciliation pass found.
     *
     * @param findings one finding per operation, in the order the store holds them
     * @param examined how many operations were looked at
     */
    public record Reconciliation(List<Finding> findings, long examined) {

        /** Holds findings nothing can change afterwards. */
        public Reconciliation {
            findings = List.copyOf(findings);
        }

        /**
         * The findings with one disposition.
         *
         * @param disposition which answer
         * @return the findings that carry it
         */
        public List<Finding> with(RecoveryDisposition disposition) {
            return findings.stream()
                    .filter(finding -> finding.disposition() == disposition)
                    .toList();
        }
    }

    /**
     * How often this runs when nothing restarts, which the contract declares and this build does
     * not.
     *
     * @param contract the authenticated contract
     * @return the interval in milliseconds
     */
    public static long intervalMilliseconds(AgentContract contract) {
        return contract.value(ContractLimit.RECOVERY_RECONCILIATION_INTERVAL_MILLISECONDS);
    }

    /**
     * Reads every operation in the served generation and says what each one is.
     *
     * @param session the session to read under
     * @param generation the incarnation being served
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every value compared here
     * @return one finding per operation
     * @throws RepositoryException if the repository fails
     */
    public static Reconciliation reconcile(Session session, EventStoreGeneration generation,
                                           long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final List<Finding> findings = new ArrayList<>();
        for (final StatePath operation : operations(session, generation)) {
            findings.add(examine(session, operation, nowUnixMilliseconds, contract));
        }
        return new Reconciliation(findings, findings.size());
    }

    private static List<StatePath> operations(Session session, EventStoreGeneration generation)
            throws RepositoryException {
        final StatePath root = StatePath.deployment(StatePath.OPERATIONS)
                .child("g" + generation.number());
        final List<StatePath> held = new ArrayList<>();
        if (!session.nodeExists(root.path())) {
            return held;
        }
        final NodeIterator first = session.getNode(root.path()).getNodes();
        while (first.hasNext()) {
            final Node level = first.nextNode();
            final NodeIterator second = level.getNodes();
            while (second.hasNext()) {
                final Node bucket = second.nextNode();
                final NodeIterator records = bucket.getNodes();
                while (records.hasNext()) {
                    held.add(root.child(level.getName()).child(bucket.getName())
                            .child(records.nextNode().getName()));
                }
            }
        }
        return held;
    }

    private static Finding examine(Session session, StatePath operation,
                                   long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final Node record = session.getNode(operation.path());
        final OperationState state = OperationState
                .named(record.getProperty(OperationStore.STATE).getString())
                .orElse(OperationState.ACCEPTED);
        if (state.finality() == JobEventKind.Finality.ENDS) {
            return new Finding(operation, RecoveryDisposition.FINISHED, "it ended as "
                    + state.spelling() + ", whatever a queue still holds about it");
        }
        if (leaseIsLive(record, nowUnixMilliseconds)) {
            return new Finding(operation, RecoveryDisposition.RUNNING_ELSEWHERE,
                    "a lease over it is live until "
                            + record.getNode(MaintenanceSweep.LEASE)
                                    .getProperty(MaintenanceSweep.LEASE_HELD_UNTIL).getLong());
        }
        return withoutALease(session, operation, record, new Moment(state, nowUnixMilliseconds,
                contract));
    }

    /**
     * When reconciliation is running and what it is holding everything to.
     *
     * @param state the state the record is in
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract
     */
    private record Moment(OperationState state, long nowUnixMilliseconds, AgentContract contract) {
    }

    private static Finding withoutALease(Session session, StatePath operation, Node record,
                                         Moment moment) throws RepositoryException {
        final long outstanding = outstandingIntake(record);
        if (outstanding > 0) {
            return intake(session, operation, record, moment, outstanding);
        }
        final long attempts = record.hasProperty(OperationStore.ATTEMPTS)
                ? record.getProperty(OperationStore.ATTEMPTS).getLong() : 0;
        if (attempts >= moment.contract().value(ContractLimit.MAXIMUM_LOGICAL_OUTBOX_ATTEMPTS)) {
            return new Finding(operation, RecoveryDisposition.UNDETERMINED, "it has had every"
                    + " delivery it may have and never ended, so whether its one commit landed is"
                    + " not something this side knows");
        }
        if (moment.state() == OperationState.ACCEPTED) {
            return new Finding(operation, RecoveryDisposition.RESTARTABLE, "it was accepted and"
                    + " never started, and a resend under the same identifier is what starts it");
        }
        return started(session, operation, record, moment);
    }

    private static Finding started(Session session, StatePath operation, Node record, Moment moment)
            throws RepositoryException {
        final long budget = moment.contract()
                .value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS)
                + moment.contract().value(ContractLimit.RECOVERY_UNDETERMINED_MARGIN_MILLISECONDS);
        final long startedAt = startedAt(session, operation, record);
        if (moment.nowUnixMilliseconds() >= startedAt + budget) {
            return new Finding(operation, RecoveryDisposition.UNDETERMINED, "it started at "
                    + startedAt + ", which is further back than the " + budget + " a command runs"
                    + " in, so the process that was running it is gone");
        }
        return new Finding(operation, RecoveryDisposition.STILL_RUNNING, "it started at "
                + startedAt + " and is inside the " + budget + " a command runs in");
    }

    private static long startedAt(Session session, StatePath operation, Node record)
            throws RepositoryException {
        final String ledger = operation.child(EventLedger.NODE).path();
        long started = record.hasProperty(RetentionPolicy.REQUEST_START)
                ? record.getProperty(RetentionPolicy.REQUEST_START).getLong() : 0;
        if (!session.nodeExists(ledger)) {
            return started;
        }
        final NodeIterator events = session.getNode(ledger).getNodes();
        while (events.hasNext()) {
            final Node event = events.nextNode();
            if (JobEventKind.STARTED.spelling()
                    .equals(event.getProperty(EventLedger.KIND).getString())) {
                started = event.getProperty(EventLedger.WRITTEN_AT).getLong();
            }
        }
        return started;
    }

    private static Finding intake(Session session, StatePath operation, Node record, Moment moment,
                                  long outstanding) throws RepositoryException {
        final RetentionPolicy.Outcome until = RetentionPolicy.until(session, operation,
                RetentionPolicy.Kind.OPERATION_DETAIL, moment.contract());
        if (until instanceof final RetentionPolicy.Held held
                && held.retainedUntil().hasPassed(moment.nowUnixMilliseconds())) {
            release(session, record, moment.contract());
            return new Finding(operation, RecoveryDisposition.ABANDONED, outstanding
                    + " declared payloads never arrived and its retention has passed, so nobody is"
                    + " waiting for it");
        }
        return new Finding(operation, RecoveryDisposition.AWAITING_INTAKE, outstanding
                + " declared payloads have not arrived and there is still time for them to");
    }

    private static void release(Session session, Node record, AgentContract contract)
            throws RepositoryException {
        final StatePath.Outcome caller = StatePath.caller(record.hasProperty(
                MaintenanceSweep.CALLER) ? record.getProperty(MaintenanceSweep.CALLER).getString()
                : "");
        if (!(caller instanceof final StatePath.Held named) || !record.hasNode(INTAKE)) {
            return;
        }
        final NodeIterator held = record.getNode(INTAKE).getNodes();
        final List<String> outstanding = new ArrayList<>();
        while (held.hasNext()) {
            final Node slot = held.nextNode();
            if (!committed(record, slot.getName())) {
                outstanding.add(slot.getName());
            }
        }
        for (final String slot : outstanding) {
            final Node declared = record.getNode(INTAKE).getNode(slot);
            final long bytes = declared.hasProperty(DECLARED_BYTES)
                    ? declared.getProperty(DECLARED_BYTES).getLong() : 0;
            // The slot goes with the reservation it stands for. A declaration nobody is waiting for
            // that stayed behind would be released again on the next pass, and a count released
            // twice is a store that believes it has room it does not have.
            declared.remove();
            session.save();
            CapacityLedger.release(session, AccountedQuantity.OPERATION_RESERVATION_ROWS,
                    named.caller(), 1, contract);
            CapacityLedger.release(session, AccountedQuantity.OPERATION_RESERVATION_BYTES,
                    named.caller(), bytes, contract);
        }
    }

    private static long outstandingIntake(Node record) throws RepositoryException {
        if (!record.hasNode(INTAKE)) {
            return 0;
        }
        final NodeIterator slots = record.getNode(INTAKE).getNodes();
        long outstanding = 0;
        while (slots.hasNext()) {
            if (!committed(record, slots.nextNode().getName())) {
                outstanding = outstanding + 1;
            }
        }
        return outstanding;
    }

    private static boolean committed(Node record, String slot) throws RepositoryException {
        return record.hasNode(ArtifactStore.NODE)
                && record.getNode(ArtifactStore.NODE).hasNode(slot);
    }

    private static boolean leaseIsLive(Node record, long nowUnixMilliseconds)
            throws RepositoryException {
        return record.hasNode(MaintenanceSweep.LEASE)
                && record.getNode(MaintenanceSweep.LEASE).hasProperty(
                        MaintenanceSweep.LEASE_HELD_UNTIL)
                && record.getNode(MaintenanceSweep.LEASE)
                        .getProperty(MaintenanceSweep.LEASE_HELD_UNTIL).getLong()
                        > nowUnixMilliseconds;
    }
}
