// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import java.util.Arrays;
import java.util.List;

/**
 * The shape every one of this agent's health checks answers in.
 *
 * <p>One verdict and one sentence, and the sentence is the whole value. An operator reading a
 * dashboard is deciding whether to act, and "capacity exceeded" tells them less than nothing —
 * whereas "the operation store holds 41,200 of 40,000 permitted records; the sweep last ran never"
 * tells them what to do next without opening anything.</p>
 *
 * <p>Six separate checks rather than one aggregate, because an aggregate answers "something is
 * wrong" and every one of these has a different somebody-to-fix-it.</p>
 */
public final class AgentHealth {

    private AgentHealth() {
    }

    /** What one check found. */
    public enum Verdict {
        /** It is as it should be. */
        HEALTHY,
        /** It is not, and somebody has to do something. */
        UNHEALTHY,
        /**
         * Nobody could tell.
         *
         * <p>Distinct from unhealthy on purpose: a check that could not run is not evidence that
         * the thing it checks is broken, and a dashboard that showed the two the same way would
         * have operators chasing a store that is fine because the check beside it timed out.</p>
         */
        UNKNOWN
    }

    /**
     * What one check answered.
     *
     * @param name which check this is
     * @param verdict what it found
     * @param detail one sentence an operator can act on, naming what it saw
     */
    public record Result(String name, Verdict verdict, String detail) {
    }

    /**
     * The tag every one of these carries, so an operator can select this agent's checks as a set
     * without knowing any of their names.
     */
    public static final String AGENT_TAG = "slingshot-agent";

    /** The six checks this agent publishes, and there is no seventh. */
    public enum Check {
        /** The state tree exists and its access-control entries are the declared ones. */
        STATE_TREE("state-tree", "storage"),
        /** A key ring exists and can issue a token that validates. */
        CONTINUATION_AUTHORITY("continuation-authority", "security"),
        /** Every counted thing is within the bound the contract states. */
        CAPACITY("capacity", "capacity"),
        /** Which deployment row this instance matches, and whether this build claims it. */
        DEPLOYMENT_ROW("deployment-row", "deployment"),
        /** Every route the table declares is registered and reachable here. */
        ROUTE_REGISTRATION("route-registration", "routing"),
        /** Every declared query is answered by an index rather than by a walk. */
        QUERY_COVERAGE("query-coverage", "queries");

        private final String spelling;
        private final String concern;

        Check(String spelling, String concern) {
            this.spelling = spelling;
            this.concern = concern;
        }

        /**
         * The tags this check is selected by on a dashboard.
         *
         * <p>Two: the one every check of this agent's carries, and the one naming what this check
         * alone is about. An operator watching storage and an operator watching this product are
         * two different people asking two different questions, and a single tag would serve one of
         * them.</p>
         *
         * @return the tags, the shared one first
         */
        public List<String> tags() {
            return List.of(AGENT_TAG, concern);
        }

        /**
         * How the dashboard names this check.
         *
         * @return the name
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The check one name means.
         *
         * @param spelled what was written
         * @return the check, or nothing where nothing is named that way
         */
        public static java.util.Optional<Check> named(String spelled) {
            return Arrays.stream(values())
                    .filter(check -> check.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Every tag any of these checks carries, each once.
         *
         * @return the tags, in the order the checks declare them
         */
        public static List<String> allTags() {
            return Arrays.stream(values())
                    .flatMap(check -> check.tags().stream())
                    .distinct()
                    .toList();
        }

        /**
         * Every check, named as the dashboard names it.
         *
         * @return the names, in declaration order
         */
        public static List<String> names() {
            return Arrays.stream(values()).map(Check::spelling).toList();
        }
    }

    /**
     * A check that found what it should.
     *
     * @param check which check
     * @param detail what it saw
     * @return the result
     */
    public static Result healthy(Check check, String detail) {
        return new Result(check.spelling(), Verdict.HEALTHY, detail);
    }

    /**
     * One that did not.
     *
     * @param check which check
     * @param detail what it saw and what to do about it
     * @return the result
     */
    public static Result unhealthy(Check check, String detail) {
        return new Result(check.spelling(), Verdict.UNHEALTHY, detail);
    }

    /**
     * One that could not tell.
     *
     * @param check which check
     * @param detail why it could not
     * @return the result
     */
    public static Result unknown(Check check, String detail) {
        return new Result(check.spelling(), Verdict.UNKNOWN, detail);
    }
}
