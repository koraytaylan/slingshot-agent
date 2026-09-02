// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.wire.CommandFailure;

/**
 * What a caller is told when a continuation token is not honoured.
 *
 * <p>Six ways a token fails and six answers, each mapped here and nowhere else. There is no default
 * branch: the mapping is a switch over a closed enumeration, so a seventh refusal added to the
 * token cannot reach a caller wearing whichever category happened to be the fallback. It would stop
 * the compiler instead, which is the only place a missing case is cheap to find.</p>
 *
 * <p>The categories are not interchangeable, because a caller does different things with them. A
 * malformed or forged token is the caller's own to fix and never worth retrying, so both are
 * argument failures. A token for another partition or another query is well-formed and genuinely
 * belongs somewhere, which is a conflict rather than a bad argument. A token whose store has been
 * rebuilt, or which has simply aged out, names a position that no longer exists — the enumeration
 * it belonged to is gone, and the answer is to begin again rather than to correct anything.</p>
 */
public final class ContinuationRefusal {

    private ContinuationRefusal() {
    }

    /**
     * The category one refusal is reported under.
     *
     * @param refusal why the token was not honoured
     * @return the category the client declares for it
     */
    public static CommandFailure.Category categoryOf(ContinuationToken.Refusal refusal) {
        return switch (refusal) {
            case MALFORMED, INTEGRITY_INVALID -> CommandFailure.Category.ARGUMENT_REJECTED;
            case WRONG_TARGET, WRONG_QUERY -> CommandFailure.Category.CONFLICT;
            case WRONG_GENERATION, EXPIRED -> CommandFailure.Category.NOT_FOUND;
        };
    }

    /**
     * What to tell a caller about one refusal, which never repeats the token back to them.
     *
     * @param refusal why the token was not honoured
     * @return the sentence naming what happened and what to do about it
     */
    public static String detailOf(ContinuationToken.Refusal refusal) {
        return switch (refusal) {
            case MALFORMED -> "the continuation token is not the shape a token has";
            case INTEGRITY_INVALID -> "the continuation token is not signed by any key this agent"
                    + " holds";
            case WRONG_TARGET -> "the continuation token belongs to another author target";
            case WRONG_QUERY -> "the continuation token belongs to another query; a position in one"
                    + " result set is not a position in another";
            case WRONG_GENERATION -> "the event store has been rebuilt since the continuation token"
                    + " was issued, so the enumeration it names is gone; begin again";
            case EXPIRED -> "the continuation token has expired; begin again";
        };
    }
}
