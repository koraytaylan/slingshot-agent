// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What a clock does when it is not the clock everybody assumed, and where that matters.
 *
 * <p>Every lease and every retention decision is a comparison of two instants, and the failure mode
 * of a skewed clock is two nodes both believing they hold one lease. So the property is always the
 * same shape and never about accuracy: a decision may be conservative and may never be wrong.
 * Waiting longer than necessary costs somebody a few seconds; deciding early costs them a second
 * effect.</p>
 *
 * <p>The disruptions are the three a real deployment produces. Skew is two machines that were never
 * synchronised; a pause is a virtual machine that was suspended; and a jump is what a time service
 * does when it corrects one — forwards or backwards, and backwards is the one nobody plans for.</p>
 */
public final class ClockDisruptor {

    /** What is done to a clock. */
    public enum Disruption {

        /** One node's clock runs ahead of the other's by the declared skew. */
        SKEWED_AHEAD("skewed_ahead"),

        /** One node's runs behind by it. */
        SKEWED_BEHIND("skewed_behind"),

        /** One node's stops, which is a machine that was suspended. */
        PAUSED("paused"),

        /** One node's is corrected forwards, which strands anything measured against it. */
        JUMPED_FORWARD("jumped_forward"),

        /** One node's is corrected backwards, which is the one nobody plans for. */
        JUMPED_BACKWARD("jumped_backward");

        private final String spelling;

        Disruption(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this disruption is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The disruption one spelling names.
         *
         * @param spelling the spelling
         * @return the disruption, or nothing where no such disruption is enumerated
         */
        public static Optional<Disruption> named(String spelling) {
            return Arrays.stream(values())
                    .filter(disruption -> disruption.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * Every comparison of two instants this repository makes, enumerated.
     *
     * <p>Enumerated rather than found, because the one that is missed is the one nobody thought was
     * a time comparison — and each carries which way being wrong is safe, which is the whole
     * property.</p>
     */
    public enum Comparison {

        /** Whether a lease is still held, where holding it early is a second worker. */
        THE_LEASE("the_lease", Conservative.DECIDE_LATE),

        /** Whether a lease may be renewed, which is the same comparison from the holder's side. */
        THE_LEASE_RENEWAL("the_lease_renewal", Conservative.DECIDE_LATE),

        /** Whether a record may be collected, where collecting early loses somebody's answer. */
        RETENTION("retention", Conservative.DECIDE_LATE),

        /** Whether a token has expired, where accepting one late is a token that outlived its key. */
        TOKEN_EXPIRY("token_expiry", Conservative.DECIDE_EARLY),

        /** How long a rotated-out key is kept, where dropping it early strands a live token. */
        KEY_RING_PRIOR_RETENTION("key_ring_prior_retention", Conservative.DECIDE_LATE),

        /** How long one stream may stay open, where holding it late spends somebody's budget. */
        THE_STREAM_SESSION_BOUND("the_stream_session_bound", Conservative.DECIDE_EARLY),

        /** How long an operation may be missing before it is called missing. */
        THE_MISSING_OPERATION_GRACE("the_missing_operation_grace", Conservative.DECIDE_LATE);

        private final String spelling;
        private final Conservative conservative;

        Comparison(String spelling, Conservative conservative) {
            this.spelling = spelling;
            this.conservative = conservative;
        }

        /**
         * How this comparison is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * Which way being wrong is safe for this comparison.
         *
         * @return the conservative direction
         */
        public Conservative conservative() {
            return conservative;
        }

        /**
         * The comparison one spelling names.
         *
         * @param spelling the spelling
         * @return the comparison, or nothing where no such comparison is enumerated
         */
        public static Optional<Comparison> named(String spelling) {
            return Arrays.stream(values())
                    .filter(comparison -> comparison.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /** Which way a decision may be wrong without anybody losing anything. */
    public enum Conservative {
        /** Deciding later than the instant says, which costs time and never correctness. */
        DECIDE_LATE,
        /** Deciding earlier than it says, which refuses something that was still valid. */
        DECIDE_EARLY
    }

    private ClockDisruptor() {
    }

    /**
     * Every disruption applied to every comparison, which is what the suite runs.
     *
     * @return one entry per pair, in a stable order
     */
    public static List<String> everyDisruption() {
        return Arrays.stream(Comparison.values())
                .flatMap(comparison -> Arrays.stream(Disruption.values())
                        .map(disruption -> disruption.spelling() + "@" + comparison.spelling()))
                .toList();
    }

    /**
     * Whether one key ring's prior retention outlasts the longest token under maximum skew.
     *
     * <p>The relation that makes a rotation safe. A key dropped while a token signed by it is still
     * inside its lifetime is a caller whose next page is refused for a reason they cannot see and
     * cannot fix — so the retention has to cover the lifetime <em>and</em> the skew between the
     * node that issued the token and the node that reads it.</p>
     *
     * @param priorRetentionMilliseconds how long a rotated-out key is kept
     * @param tokenLifetimeMilliseconds the longest a token stays valid
     * @param skewAllowanceMilliseconds the disagreement between two clocks that is tolerated
     * @return whether the retention covers both
     */
    public static boolean priorRetentionCoversTheSkew(long priorRetentionMilliseconds,
                                                      long tokenLifetimeMilliseconds,
                                                      long skewAllowanceMilliseconds) {
        return priorRetentionMilliseconds >= tokenLifetimeMilliseconds + skewAllowanceMilliseconds;
    }
}
