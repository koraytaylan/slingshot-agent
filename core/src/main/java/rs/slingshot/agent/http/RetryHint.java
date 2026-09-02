// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * How long this side asks a client to wait, where waiting can help at all.
 *
 * <p>A hint on a refusal that will never succeed is an instruction to waste an author's request
 * budget, so a refusal that is not retryable carries none — and asking for one is refused rather
 * than answered with zero, because zero is a hint that says "immediately".</p>
 *
 * <p>A hint longer than the client's own cap is refused rather than clamped. Clamping would mean
 * this side asked for one thing and the client did another, and the two would disagree about when
 * the next request is due; refusing means somebody notices that the deployment is asking for
 * something the protocol does not allow.</p>
 *
 * @param milliseconds how long this side asks the caller to wait
 */
public record RetryHint(long milliseconds) {

    /** Why there is no hint. */
    public enum Refusal {
        /** The refusal it was asked for is one that trying again cannot fix. */
        NOT_RETRYABLE,
        /** It is longer than the cap the contract declares. */
        PAST_THE_CAP
    }

    /** The result of asking for one. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A hint this side will send.
     *
     * @param hint how long to wait
     */
    public record Held(RetryHint hint) implements Outcome {
    }

    /**
     * No hint, and the reason there is none.
     *
     * @param refusal why not
     * @param detail what was observed, naming both numbers where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * The hint for one refusal, where that refusal is one waiting can help.
     *
     * @param row the mapping row the refusal falls under
     * @param milliseconds how long this side would like the caller to wait
     * @param contract the authenticated contract, which declares the cap
     * @return the hint, or the one reason there is none
     */
    public static Outcome of(StatusMapping.Row row, long milliseconds, AgentContract contract) {
        if (!row.isWorthTryingAgain() || !row.carriesAhint()) {
            return new Refused(Refusal.NOT_RETRYABLE, row.category()
                    + " is not a refusal that trying again fixes, so a hint would be an"
                    + " instruction to waste a request");
        }
        final long cap = contract.value(ContractLimit.RETRY_AFTER_CAP_MILLISECONDS);
        if (milliseconds > cap) {
            return new Refused(Refusal.PAST_THE_CAP, milliseconds
                    + " is longer than the cap of " + cap + " the contract declares, and clamping"
                    + " it would leave the two sides disagreeing about when the next request is"
                    + " due");
        }
        return new Held(new RetryHint(milliseconds));
    }

    /**
     * How this hint is written where a client reads it, which is in whole seconds.
     *
     * @return the seconds, never below one, because zero would mean immediately
     */
    public long seconds() {
        return Math.max(1, milliseconds / MILLISECONDS_IN_A_SECOND);
    }

    /** How many milliseconds a second is, where a header is written in seconds. */
    private static final long MILLISECONDS_IN_A_SECOND = 1000;

    /**
     * The one reason there is no hint, where there is none.
     *
     * @param outcome what asking for one produced
     * @return the refusal, or nothing where there is a hint
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
