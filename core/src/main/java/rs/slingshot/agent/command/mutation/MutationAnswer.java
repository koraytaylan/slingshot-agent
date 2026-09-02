// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import rs.slingshot.agent.command.CommandHandler;

/**
 * How a mutation's three answers reach the one dispatch understands.
 *
 * <p>Written once because twenty handlers do it, and twenty copies of this mapping is twenty places
 * for the third answer to be flattened into the second. The unknown outcome reaches the caller as
 * its own category and never as an ordinary failure — a caller told a write failed retries it, and
 * a retry of a write that landed is a second effect on their repository.</p>
 */
public final class MutationAnswer {

    private MutationAnswer() {
    }

    /**
     * The answer one held-to-one-commit run produces.
     *
     * <p>A breach of the commit rule is reported as the commit having failed, because from the
     * caller's side that is what it is: the repository does not hold what they asked for, and the
     * reason is this side's own rather than theirs.</p>
     *
     * <p>The unknown category is named by the caller rather than assumed, because there are three
     * of them and which one a command owes is its registry row's business. A repository mutation
     * whose commit was interrupted and an admission this process never heard back about are both
     * outcomes nobody knows, and reporting either under the other's name hands the caller a
     * category their own half has never heard of.</p>
     *
     * @param outcome what the wrapper produced
     * @param commitFailed the category this command reports a failed commit under
     * @param outcomeUnknown the category this command's row declares for the answer nobody knows
     * @return the answer
     */
    public static CommandHandler.Answer of(SingleCommit.Outcome outcome, String commitFailed,
                                           String outcomeUnknown) {
        // Matched over the sealed set rather than tested and cast, so a third kind of outcome
        // stops the compiler here instead of arriving as a cast that fails at run time.
        return switch (outcome) {
            case SingleCommit.Refused refused -> new CommandHandler.Failed(commitFailed,
                    refused.breach() + " after " + refused.commits() + " commits");
            case SingleCommit.Ran ran -> answered(ran.outcome(), outcomeUnknown);
        };
    }

    /**
     * What one mutation's own answer is, once the commit rule has had its say.
     *
     * <p>The commit category is not needed here: a mutation that refused says under which of its
     * own categories it did, and one that does not know says so under the shared one.</p>
     *
     * @param outcome what the mutation answered
     * @param outcomeUnknown the category this command's row declares for the answer nobody knows
     * @return the answer
     */
    private static CommandHandler.Answer answered(MutationOutcome outcome,
                                                  String outcomeUnknown) {
        return switch (outcome) {
            case MutationOutcome.Changed changed ->
                    new CommandHandler.Produced(changed.result());
            case MutationOutcome.Refused held ->
                    new CommandHandler.Failed(held.category(), held.detail());
            case MutationOutcome.Unknown held ->
                    new CommandHandler.Failed(outcomeUnknown, held.detail());
        };
    }
}
