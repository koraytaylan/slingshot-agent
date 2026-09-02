// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * What a command may spend, decided before it starts and not by it.
 *
 * <p>Three of them, and each has exactly one way of being exceeded, so a command that ran out of
 * one is reported as having run out of that one. A single "too much" category would tell a caller
 * that something was too large without saying which thing, which is the difference between a
 * message they can act on and one they can only forward.</p>
 *
 * <p>The numbers are the registry row's or the contract's. A budget declared inside a handler
 * package would be a fortieth command deciding for itself what an author instance can afford.</p>
 *
 * @param kind which budget this is
 * @param limit the number it is
 */
public record Budget(Kind kind, long limit) {

    /** The three things a command spends, and there is no fourth. */
    public enum Kind {
        /** How many rows a query may examine, which is what keeps a command off a traversal. */
        DISCOVERY("discovery_budget_exceeded"),
        /** How long a command may run, bounded below the smallest request window declared. */
        TIME("time_budget_exceeded"),
        /** How large a result may be before it overflows into an artifact rather than truncating. */
        RESULT("result_budget_exceeded");

        private final String category;

        Kind(String category) {
            this.category = category;
        }

        /**
         * The one category exceeding this budget is reported as.
         *
         * @return the category, which is on the wire and is the client's own spelling
         */
        public String category() {
            return category;
        }
    }

    /**
     * Holds a budget that is a number rather than an absence of one.
     *
     * @throws IllegalArgumentException if the limit is not positive, because an unbounded budget is
     *     not a budget
     */
    public Budget {
        if (limit <= 0) {
            throw new IllegalArgumentException(kind + " is not bounded, and an unbounded budget is"
                    + " a command deciding for itself what an author instance can afford");
        }
    }

    /**
     * Whether a spend is inside this budget.
     *
     * @param spent what has been spent
     * @return whether it may go on
     */
    public boolean allows(long spent) {
        return spent <= limit;
    }

    /**
     * How many rows a command may examine, which is the contract's own bound.
     *
     * @param contract the authenticated contract
     * @return the budget
     */
    public static Budget discovery(AgentContract contract) {
        return new Budget(Kind.DISCOVERY,
                contract.value(ContractLimit.MAINTENANCE_SWEEP_WORK_BOUND_ROWS));
    }

    /**
     * How long a command may run, which the contract bounds below every declared request window.
     *
     * @param contract the authenticated contract
     * @return the budget
     */
    public static Budget time(AgentContract contract) {
        return new Budget(Kind.TIME,
                contract.value(ContractLimit.MAXIMUM_COMMAND_EXECUTION_MILLISECONDS));
    }

    /**
     * How large one command's result may be, which is its own row's bound.
     *
     * @param row the command's own registry row
     * @return the budget
     */
    public static Budget result(RegistryRow row) {
        return new Budget(Kind.RESULT, row.resultBytes());
    }
}
