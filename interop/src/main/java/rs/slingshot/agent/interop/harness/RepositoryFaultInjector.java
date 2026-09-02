// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The four ways a repository refuses a write, injected at a named point.
 *
 * <p>Every durable guarantee in this repository is a claim about what a commit does, and the four
 * are four different claims. A commit that failed is retryable; a commit that conflicted is
 * contention and retryable a bounded number of times; a store that is full is an admission that
 * should never have been made rather than a write that should be retried; and a session invalidated
 * mid-operation is the one where this side cannot say whether anything landed. Code that treated
 * any two of them alike would recover wrongly for one.</p>
 *
 * <p>Injecting rather than waiting for one is the point: three of the four are rare enough that a
 * suite that waited for them would be a suite that never ran them, and rare is exactly what makes
 * the recovery path the least-exercised code in the product.</p>
 */
public final class RepositoryFaultInjector {

    /** What the repository does instead of committing. */
    public enum Fault {

        /** The commit fails, and nothing landed. */
        COMMIT_FAILS("commit_fails", Disposition.RETRYABLE),

        /** Another writer got there first, which is contention rather than failure. */
        COMMIT_CONFLICTS("commit_conflicts", Disposition.CONTENTION),

        /** There is no room, which is an admission that should not have been made. */
        STORE_IS_FULL("store_is_full", Disposition.ADMISSION_REFUSED),

        /**
         * The session went away mid-operation.
         *
         * <p>The only one of the four where this side cannot say whether the write landed, which is
         * why it is its own fault rather than a kind of failure.</p>
         */
        SESSION_INVALIDATED("session_invalidated", Disposition.OUTCOME_UNKNOWN);

        private final String spelling;
        private final Disposition disposition;

        Fault(String spelling, Disposition disposition) {
            this.spelling = spelling;
            this.disposition = disposition;
        }

        /**
         * How this fault is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * What a caller is told when this fault happens, which is different for all four.
         *
         * @return the disposition
         */
        public Disposition disposition() {
            return disposition;
        }

        /**
         * The fault one spelling names.
         *
         * @param spelling the spelling
         * @return the fault, or nothing where no such fault is enumerated
         */
        public static Optional<Fault> named(String spelling) {
            return Arrays.stream(values())
                    .filter(fault -> fault.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /** What each fault becomes by the time a caller hears about it, and no two are the same. */
    public enum Disposition {
        /** Try it again; nothing landed. */
        RETRYABLE,
        /** Somebody else got there first; try again within the declared bound. */
        CONTENTION,
        /** There is no room, so nothing should have been admitted. */
        ADMISSION_REFUSED,
        /** Nobody knows whether it landed, which is an answer rather than a failure. */
        OUTCOME_UNKNOWN
    }

    /**
     * Where a write may be refused, enumerated across everything this store commits.
     *
     * <p>Named rather than numbered, because a scenario reporting "point four" tells nobody what
     * broke — and these are the points a recovery path has to cross.</p>
     */
    public enum Point {

        /** The claim that makes one submission one operation. */
        THE_ADMISSION_CLAIM("the_admission_claim"),

        /** The claim that puts work in the outbox exactly once. */
        THE_OUTBOX_CLAIM("the_outbox_claim"),

        /** The claim that makes one worker the holder. */
        THE_LEASE_CLAIM("the_lease_claim"),

        /** Any move from one operation state to another. */
        A_STATE_TRANSITION("a_state_transition"),

        /** Any append to the event ledger. */
        AN_EVENT_APPEND("an_event_append"),

        /** Any write of an artifact's bytes. */
        AN_ARTIFACT_WRITE("an_artifact_write"),

        /** The one commit that lands the state, the answer, the last event and the snapshot. */
        THE_TERMINAL_COMMIT("the_terminal_commit");

        private final String spelling;

        Point(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this point is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The point one spelling names.
         *
         * @param spelling the spelling
         * @return the point, or nothing where no such point is enumerated
         */
        public static Optional<Point> named(String spelling) {
            return Arrays.stream(values())
                    .filter(point -> point.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * One injection: a fault, at a point.
     *
     * @param fault what the repository does instead of committing
     * @param point where it does it
     */
    public record Injection(Fault fault, Point point) {

        /**
         * How this injection is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return fault.spelling() + "@" + point.spelling();
        }
    }

    private RepositoryFaultInjector() {
    }

    /**
     * Every injection this suite runs: all four faults at every point, and no others.
     *
     * <p>The cross product rather than a chosen subset, because the ones somebody would leave out
     * are the ones nobody has thought about — and twenty-eight is a number a suite can afford.</p>
     *
     * @return the injections, in a stable order
     */
    public static List<Injection> everyInjection() {
        return Arrays.stream(Point.values())
                .flatMap(point -> Arrays.stream(Fault.values())
                        .map(fault -> new Injection(fault, point)))
                .toList();
    }

    /**
     * Whether the four faults stay four distinguishable answers.
     *
     * <p>Asked as a property rather than assumed: the day two faults map to one disposition, the
     * recovery for one of them is running for the other.</p>
     *
     * @return whether every fault has a disposition no other fault has
     */
    public static boolean dispositionsAreDistinct() {
        return Arrays.stream(Fault.values()).map(Fault::disposition).distinct().count()
                == Fault.values().length;
    }
}
