// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.store.GenerationStore;
import rs.slingshot.agent.store.StatePath;
import rs.slingshot.agent.store.WriteOutcome;

/**
 * Whether a submission is new work, work this side already has, or another piece of work wearing a
 * name it has already recorded.
 *
 * <p>This is the property the client's whole recovery story rests on. A request that left the
 * client and never produced an answer prompts a lookup rather than a resend, and a resend that does
 * happen converges on the same durable thing rather than creating a second one.</p>
 *
 * <p>The digest compared here is the one this side derived from the request itself, never the key
 * the caller supplied: a key nobody recomputed is a caller asserting what its own request means.
 * The target identity digest and the environment revision are compared beside it, because the
 * derivation does not cover them — the same command aimed at another target, or at another revision
 * of the caller's environment, is different work.</p>
 */
public final class SubmissionAdmission {

    private SubmissionAdmission() {
    }

    /**
     * One submission as it arrives, before anything has been decided about it.
     *
     * @param identity which operation, at which incarnation, against which target, at which
     *     revision
     * @param submissionDigest the digest this side derived from the request itself
     * @param commandContract which command contract it means
     * @param caller who submitted it
     * @param requestStartUnixMilliseconds when the client says its request began
     */
    public record Submission(OperationIdentity identity, DigestValue submissionDigest,
                             CommandContractIdentity commandContract, StatePath.Caller caller,
                             long requestStartUnixMilliseconds) {
    }

    /**
     * Admits a submission, or recognises it, or refuses it.
     *
     * @param session the session to read and write under
     * @param submission what arrived
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares every bound
     * @return what this side did with it
     * @throws RepositoryException if the repository fails
     */
    public static AdmissionOutcome admit(Session session, Submission submission,
                                         long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final GenerationStore.Membership membership =
                GenerationStore.membership(session, submission.identity().generation());
        if (membership != GenerationStore.Membership.SERVING) {
            return new AdmissionOutcome.Refused(reasonFor(membership),
                    "this store does not serve generation "
                            + submission.identity().generation());
        }
        final OperationStore.Outcome existing =
                OperationStore.read(session, submission.identity());
        if (existing instanceof final OperationStore.Held held) {
            return compared(held.operation(), submission);
        }
        return recorded(session, submission, nowUnixMilliseconds, contract);
    }

    private static AdmissionOutcome.Reason reasonFor(GenerationStore.Membership membership) {
        return membership == GenerationStore.Membership.RETAINED
                ? AdmissionOutcome.Reason.RETAINED_GENERATION
                : AdmissionOutcome.Reason.UNKNOWN_GENERATION;
    }

    private static AdmissionOutcome recorded(Session session, Submission submission,
                                             long nowUnixMilliseconds, AgentContract contract)
            throws RepositoryException {
        final LogicalOperation.Outcome accepted = LogicalOperation.accepted(submission.identity(),
                submission.submissionDigest(), submission.commandContract(), submission.caller(),
                submission.requestStartUnixMilliseconds(), nowUnixMilliseconds, contract);
        if (accepted instanceof final LogicalOperation.Refused refused) {
            return new AdmissionOutcome.Refused(
                    AdmissionOutcome.Reason.UNBELIEVABLE_REQUEST_START, refused.detail());
        }
        final Object created = OperationStore.create(session,
                ((LogicalOperation.Held) accepted).operation());
        if (created instanceof final OperationStore.Refused refused) {
            return new AdmissionOutcome.Refused(AdmissionOutcome.Reason.NOT_RECORDED,
                    refused.refusal() + ": " + refused.detail());
        }
        final OperationStore.Created record = (OperationStore.Created) created;
        if (record.outcome() == WriteOutcome.CLAIMED) {
            return new AdmissionOutcome.Accepted(record.operation());
        }
        // Somebody else recorded it between the read and the claim, which is the ordinary race a
        // resend arriving twice produces. What is there is compared like any other resend.
        return compared(record.operation(), submission);
    }

    private static AdmissionOutcome compared(LogicalOperation recorded, Submission submission) {
        if (!recorded.submissionDigest().matches(submission.submissionDigest())) {
            return new AdmissionOutcome.Conflicting("submission_digest",
                    "this identifier already names a different submission");
        }
        if (!recorded.identity().targetDigest().matches(submission.identity().targetDigest())) {
            return new AdmissionOutcome.Conflicting("author_target_identity_digest",
                    "this identifier already names work against another target");
        }
        if (!recorded.identity().environmentRevision()
                .equals(submission.identity().environmentRevision())) {
            return new AdmissionOutcome.Conflicting("selected_environment_revision",
                    "this identifier already names work against another revision of the caller's"
                            + " environment");
        }
        return new AdmissionOutcome.Recognised(recorded);
    }

    /**
     * Starts an operation nothing has started, on the resending caller's own request.
     *
     * <p>This is the liveness path an immediate command has instead of a redelivery: a process that
     * stopped between admitting a submission and starting its work leaves a record nothing will
     * move, and the client's own resend under the same derived key is what moves it. Starting is a
     * compare-and-set from accepted, so a resend that races the original loses harmlessly rather
     * than producing a second execution.</p>
     *
     * @param session the session to write under
     * @param recognised the record a resend was recognised as
     * @return the started record, or the one reason it did not start
     * @throws RepositoryException if the repository fails
     */
    public static OperationStore.Outcome start(Session session, LogicalOperation recognised)
            throws RepositoryException {
        return OperationStore.move(session, recognised, OperationState.RUNNING);
    }
}
