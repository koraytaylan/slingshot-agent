// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.CommandContractIdentity;
import rs.slingshot.agent.identity.OperationIdentity;
import rs.slingshot.agent.store.StatePath;

/**
 * One durable thing per operation identifier and generation: what was submitted, by whom, against
 * what, when, and where it has got to.
 *
 * <p>What the record holds is decided here and nowhere else, because every later plan that needs a
 * fact about an operation — which caller submitted it, which target it was against, which revision
 * of their environment it named — can only read one that this type wrote down.</p>
 *
 * <p>The request-start instant is the client's, and every relative retention is measured from it
 * rather than from when this record was created. A later anchor silently lengthens a window the
 * client is budgeting against, and a client that budgeted for an hour would find its work collected
 * at some other time entirely.</p>
 *
 * @param identity which operation, at which incarnation, against which target, at which revision
 * @param submissionDigest the digest this side derived from the submission itself
 * @param commandContract the five fields that say which command contract this is
 * @param caller who submitted it, which is what the read routes decide ownership against
 * @param requestStartUnixMilliseconds when the client says its request began
 * @param state where it has got to
 * @param attempts how many physical attempts have been made for it
 */
public record LogicalOperation(OperationIdentity identity, DigestValue submissionDigest,
                               CommandContractIdentity commandContract, StatePath.Caller caller,
                               long requestStartUnixMilliseconds, OperationState state,
                               long attempts) {

    /** Why a request-start instant is not one this side will record. */
    public enum ClockRefusal {
        /** It is further in the past than the contract's allowance, so the record would be swept
         * before its client could read it. */
        TOO_FAR_BEHIND,
        /** It is further in the future than the allowance, so it would hold capacity nothing
         * releases. */
        TOO_FAR_AHEAD
    }

    /** The result of holding one: the operation, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A submission this side will record.
     *
     * @param operation the record it will write
     */
    public record Held(LogicalOperation operation) implements Outcome {
    }

    /**
     * A submission it will not.
     *
     * @param refusal why it will not
     * @param detail what was observed, naming both instants
     */
    public record Refused(ClockRefusal refusal, String detail) implements Outcome {
    }

    /**
     * Holds a first record for one submission, if its request-start instant is one this side's own
     * clock can believe.
     *
     * @param identity which operation this is
     * @param submissionDigest the digest derived from the submission itself
     * @param commandContract which command contract it means
     * @param caller who submitted it
     * @param requestStartUnixMilliseconds when the client says its request began
     * @param nowUnixMilliseconds what this side's clock says
     * @param contract the authenticated contract, which declares the allowance
     * @return the record, or the one reason there is none
     */
    public static Outcome accepted(OperationIdentity identity, DigestValue submissionDigest,
                                   CommandContractIdentity commandContract, StatePath.Caller caller,
                                   long requestStartUnixMilliseconds, long nowUnixMilliseconds,
                                   AgentContract contract) {
        final long allowance = contract.value(ContractLimit.MAXIMUM_REQUEST_START_SKEW_MILLISECONDS);
        final long apart = requestStartUnixMilliseconds - nowUnixMilliseconds;
        if (apart < -allowance) {
            return new Refused(ClockRefusal.TOO_FAR_BEHIND, "a request that began at "
                    + requestStartUnixMilliseconds + " is further behind " + nowUnixMilliseconds
                    + " than the " + allowance + " this side allows");
        }
        if (apart > allowance) {
            return new Refused(ClockRefusal.TOO_FAR_AHEAD, "a request that began at "
                    + requestStartUnixMilliseconds + " is further ahead of " + nowUnixMilliseconds
                    + " than the " + allowance + " this side allows");
        }
        return new Held(new LogicalOperation(identity, submissionDigest, commandContract, caller,
                requestStartUnixMilliseconds, OperationState.ACCEPTED, 0));
    }

    /**
     * This record in another state, if the move is one this build permits.
     *
     * @param next the state to move to
     * @return the moved record, or nothing where the move is not permitted
     */
    public java.util.Optional<LogicalOperation> moved(OperationState next) {
        if (!state.permits(next)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new LogicalOperation(identity, submissionDigest,
                commandContract, caller, requestStartUnixMilliseconds, next, attempts));
    }

    /**
     * This record with one more physical attempt counted against it.
     *
     * @return the record
     */
    public LogicalOperation attempted() {
        return new LogicalOperation(identity, submissionDigest, commandContract, caller,
                requestStartUnixMilliseconds, state, attempts + 1);
    }

    /**
     * When this record's retention runs out, measured from where the client anchors it.
     *
     * @param retentionMilliseconds how long the retention is
     * @return the instant it runs out
     */
    public long retainedUntil(long retentionMilliseconds) {
        return requestStartUnixMilliseconds + retentionMilliseconds;
    }
}
