// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The three answers a command that changes a repository can give.
 *
 * <p>It happened, and the result says what changed. It did not happen, and nothing changed. Or
 * nobody knows: the write left this process and the acknowledgement did not come back, so the
 * commit may have landed.</p>
 *
 * <p>The third is the one worth arguing about, and it is a first-class answer rather than a kind of
 * failure. A caller told "it failed" retries, and a retry of a mutation that actually landed is a
 * second effect on somebody's repository. A caller told "nobody knows" looks. That difference is
 * the whole reason this type has three shapes instead of two.</p>
 *
 * <p>Which category the third answer is reported under belongs to {@link SingleCommit}, because
 * which one it is says what kind of change a command makes — and that is the same question the
 * commit rule is about.</p>
 *
 * <p>They are mutually exclusive by construction: no answer carries both a result and a failure,
 * and the unknown one carries neither a claim of change nor a claim of no change. A record with a
 * nullable result beside a nullable category would let all three be said at once.</p>
 */
public sealed interface MutationOutcome
        permits MutationOutcome.Changed, MutationOutcome.Refused, MutationOutcome.Unknown {




    /**
     * It happened, and this is what changed.
     *
     * <p>What changed rather than that it succeeded. A caller comparing the reported address
     * against the one they asked about catches a whole class of defect that a yes cannot.</p>
     *
     * @param result what changed
     */
    record Changed(DocumentValue.Mapping result) implements MutationOutcome {
    }

    /**
     * It did not happen, and nothing changed.
     *
     * <p>The refusal is the command's own closed document rather than a category alone. Two
     * refusals under one category differ in what they are about — which target, which parent —
     * and the client authenticates that against the request it made before it believes an ending.
     * The document is optional because a command whose row declares no refusal shape of its own
     * still fails, and saying so under its category is all it owes.</p>
     *
     * @param category the declared failure category
     * @param detail what was refused, naming no content the caller cannot already see
     * @param refusal the command's own refusal document, where it produces one
     */
    record Refused(String category, String detail, CommandHandler.RefusalDocument refusal)
            implements MutationOutcome {

        /**
         * A refusal that names no document of its own.
         *
         * @param category the declared failure category
         * @param detail what was refused
         */
        public Refused(String category, String detail) {
            this(category, detail, new CommandHandler.Unstated());
        }
    }

    /**
     * Nobody knows whether it happened.
     *
     * <p>Carries no result, because there may be nothing to report, and no category of its own
     * beyond the one that says exactly this — a caller receiving it goes and looks.</p>
     *
     * @param detail what was observed, which is for this side's own record
     */
    record Unknown(String detail) implements MutationOutcome {
    }
}
