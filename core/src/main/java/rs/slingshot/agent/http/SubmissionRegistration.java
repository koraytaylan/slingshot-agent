// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.List;
import java.util.Optional;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.execution.AdmissionOutcome;
import rs.slingshot.agent.execution.OperationStore;
import rs.slingshot.agent.execution.SubmissionAdmission;
import rs.slingshot.agent.store.AccountedQuantity;
import rs.slingshot.agent.store.CapacityLedger;
import rs.slingshot.agent.store.CapacityReservation;
import rs.slingshot.agent.store.ClaimByCreation;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.SubscriptionLedger;
import rs.slingshot.agent.store.SubscriptionRecord;
import rs.slingshot.agent.store.WriteOutcome;

/** Publishes a new operation and its subscription binding in one acceptance commit. */
public final class SubmissionRegistration {

    private SubmissionRegistration() {
    }

    /**
     * The operation, subscription, and intake declarations requested together.
     *
     * @param submission the validated operation and original caller
     * @param subscription the requested subscription name
     * @param manifest the intake declarations
     */
    public record Request(SubmissionAdmission.Submission submission, String subscription,
                          List<IntakeSlotWrite.Declared> manifest) {
        /** Takes an immutable copy of the declarations. */
        public Request {
            manifest = List.copyOf(manifest);
        }
    }

    /**
     * Admits the operation with its binding, or registers a subscription to a matching resend.
     *
     * @param session the internal state session
     * @param request the operation and subscription
     * @param nowUnixMilliseconds the admission time
     * @param contract the authenticated bounds
     * @return the admission decision or capacity refusal
     * @throws RepositoryException if publication or cleanup fails
     */
    public static IntakeSlotWrite.Admission admit(Session session, Request request,
                                                   long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final SubscriptionRecord.Outcome named =
                SubscriptionRecord.identifier(request.subscription(), contract);
        if (!(named instanceof final SubscriptionRecord.Held held)) {
            return new IntakeSlotWrite.Decided(new AdmissionOutcome.Refused(
                    AdmissionOutcome.Reason.NOT_RECORDED, "the subscription identifier is invalid"));
        }
        final var submission = request.submission();
        final SubscriptionRecord record = new SubscriptionRecord(held.identifier(),
                submission.identity().generation(), new SubscriptionRecord.Binding(submission.caller(),
                submission.identity().identifier()), SubscriptionRecord.Unread.NOTHING_SHOWN_YET,
                nowUnixMilliseconds);
        if (OperationStore.read(session, submission.identity()) instanceof OperationStore.Held) {
            return registered(session, request, record, new IntakeSlotWrite.Decided(
                    SubmissionAdmission.admit(session, submission, nowUnixMilliseconds, contract)), contract);
        }
        final IntakeSlotWrite.Admission admission = retryable(created(session, request, record, contract));
        return registered(session, request, record, admission, contract);
    }

    private static IntakeSlotWrite.Admission retryable(IntakeSlotWrite.Admission admission) {
        if (admission instanceof final IntakeSlotWrite.Decided decided
                && decided.outcome() instanceof final AdmissionOutcome.Refused refused
                && refused.refusal() == AdmissionOutcome.Reason.NOT_RECORDED) {
            return new IntakeSlotWrite.NotCounted(WriteOutcome.CONTENDED);
        }
        return admission;
    }

    private static IntakeSlotWrite.Admission created(Session session, Request request,
                                                      SubscriptionRecord record, AgentContract contract)
            throws RepositoryException {
        final StatePath path = SubscriptionRecord.pathOf(record.identifier());
        if (session.nodeExists(path.path())) {
            final Optional<SubscriptionRecord> held = SubscriptionLedger.read(session, record.identifier(),
                    contract);
            if (held.isEmpty() || !held.get().binding().equals(record.binding())
                    || !held.get().generation().equals(record.generation())
                    || SubscriptionLedger.expired(held.get(),
                            record.lastAdvancedAtUnixMilliseconds(), contract)) {
                return conflicting();
            }
            return IntakeSlotWrite.admit(session, request.submission(), request.manifest(),
                    record.lastAdvancedAtUnixMilliseconds(), contract,
                    node -> SubscriptionLedger.stageHeld(node.getSession(), record, contract));
        }
        return reserved(session, request, record, contract);
    }

    private static IntakeSlotWrite.Admission reserved(Session session, Request request,
                                                       SubscriptionRecord record, AgentContract contract)
            throws RepositoryException {
        final WriteOutcome parent = ClaimByCreation.claim(session,
                StatePath.deployment(SubscriptionRecord.NODE), "nt:unstructured", node -> { });
        if (parent != WriteOutcome.CLAIMED && parent != WriteOutcome.ALREADY_HELD) {
            return new IntakeSlotWrite.NotCounted(parent);
        }
        final CapacityLedger.ReservationAdmission room = CapacityLedger.take(session,
                record.binding().caller(),
                List.of(new CapacityReservation.Charge(AccountedQuantity.ACTIVE_SUBSCRIPTION_ROWS, 1),
                        new CapacityReservation.Charge(AccountedQuantity.ACTIVE_SUBSCRIPTION_BYTES,
                                record.bytes())), contract);
        if (room instanceof final CapacityLedger.Refused refused) {
            return new IntakeSlotWrite.AtCapacity(refused);
        }
        if (room instanceof final CapacityLedger.NotCounted missed) {
            return new IntakeSlotWrite.NotCounted(missed.outcome());
        }
        final CapacityReservation reservation = ((CapacityLedger.Reserved) room).reservation();
        try (CapacityReservation.Guard guard = reservation.guard(session, contract)) {
            return IntakeSlotWrite.admit(session, request.submission(), request.manifest(),
                    record.lastAdvancedAtUnixMilliseconds(), contract,
                    node -> SubscriptionLedger.stage(node.getSession(), record, guard.reservation()));
        }
    }

    private static IntakeSlotWrite.Admission registered(Session session, Request request,
                                                         SubscriptionRecord record,
                                                         IntakeSlotWrite.Admission admission,
                                                         AgentContract contract) throws RepositoryException {
        if (!(admission instanceof final IntakeSlotWrite.Decided decided)
                || !(decided.outcome() instanceof AdmissionOutcome.Recognised)) {
            return admission;
        }
        final SubscriptionLedger.Outcome subscribed = SubscriptionLedger.subscribe(session,
                request.submission().caller(), request.subscription(), record.generation(),
                record.binding().operation(), record.lastAdvancedAtUnixMilliseconds(), contract);
        if (subscribed instanceof final SubscriptionLedger.AtCapacity refused) {
            return new IntakeSlotWrite.AtCapacity(refused.refusal());
        }
        if (subscribed instanceof final SubscriptionLedger.NotCounted missed) {
            return new IntakeSlotWrite.NotCounted(missed.notCounted().outcome());
        }
        return subscribed instanceof SubscriptionLedger.Refused ? conflicting() : admission;
    }

    private static IntakeSlotWrite.Admission conflicting() {
        return new IntakeSlotWrite.Decided(new AdmissionOutcome.Conflicting(SubmitServlet.SUBSCRIPTION,
                "the subscription name is not available for this operation"));
    }
}
