// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Every counted thing against the bound the contract states for it.
 *
 * <p>Capacity is refused at the moment it is reached, which is the moment a caller's work stops
 * and the least useful moment for an operator to first hear about it. Reported as a number beside
 * its bound, it is something somebody can watch approaching — and the sentence names every count
 * rather than only the ones that are over, because "well within every bound" and "one point away
 * from three of them" are the same verdict and very different situations.</p>
 */
public final class CapacityHealthCheck {

    private CapacityHealthCheck() {
    }

    /**
     * One counted thing.
     *
     * @param quantity what is counted, as the contract names it
     * @param held how many there are
     * @param bound how many there may be
     */
    public record Reading(String quantity, long held, long bound) {

        /**
         * How this reading is written into the sentence an operator reads.
         *
         * @return the count against its bound
         */
        public String rendered() {
            return quantity + " " + held + "/" + bound;
        }

        /**
         * Whether this one is over.
         *
         * @return whether more is held than the bound permits
         */
        public boolean isOver() {
            return held > bound;
        }
    }

    /**
     * Whether every counted thing is within its bound.
     *
     * @param readings every count, against the bound the contract states for it
     * @return one result an operator can act on
     */
    public static AgentHealth.Result of(List<Reading> readings) {
        if (readings.isEmpty()) {
            return AgentHealth.unknown(AgentHealth.Check.CAPACITY, "no count could be read, so"
                    + " nothing here can say whether anything is within its bound — which is not"
                    + " the same as saying nothing is");
        }
        final String all = readings.stream().map(Reading::rendered)
                .collect(Collectors.joining(", "));
        final List<String> over = readings.stream().filter(Reading::isOver)
                .map(Reading::rendered).toList();
        if (over.isEmpty()) {
            return AgentHealth.healthy(AgentHealth.Check.CAPACITY,
                    "every counted thing is within its bound — " + all);
        }
        return AgentHealth.unhealthy(AgentHealth.Check.CAPACITY, over.size() + " of "
                + readings.size() + " counted things are past their bound — " + over + ". Work is"
                + " being refused at the moment it is submitted; the sweep reclaims what a"
                + " retention window has released, and raising a bound is the other answer. Every"
                + " count: " + all + ".");
    }
}
