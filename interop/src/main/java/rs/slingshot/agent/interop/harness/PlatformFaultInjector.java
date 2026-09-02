// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The three ways a platform service stops answering, across every interface this agent calls.
 *
 * <p>The platform commands report what the platform said. What happens when it says nothing is the
 * case that decides whether the unknown outcome is a real answer or a category nobody can reach —
 * and a category no fault can produce is a category that does not exist, however carefully its
 * schema was written.</p>
 *
 * <p>Rejecting and throwing are deliberately one answer to a caller. A service that refuses and a
 * service that fails have both declined to do the thing, and a caller who could tell them apart
 * would be reading this product's opinion of somebody else's exception. Never answering is the
 * different one, and it is different because nobody knows whether it acted.</p>
 */
public final class PlatformFaultInjector {

    /** What a platform service does instead of answering. */
    public enum Fault {

        /** It says no, which is an answer. */
        REJECTS("rejects", Answer.DECLARED_REJECTION),

        /** It fails, which to a caller is the same answer. */
        THROWS("throws", Answer.DECLARED_REJECTION),

        /** It never answers, which is the only one where nobody knows whether it acted. */
        NEVER_ANSWERS("never_answers", Answer.OUTCOME_UNKNOWN);

        private final String spelling;
        private final Answer answer;

        Fault(String spelling, Answer answer) {
            this.spelling = spelling;
            this.answer = answer;
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
         * What a caller is told when this fault happens.
         *
         * @return the answer
         */
        public Answer answer() {
            return answer;
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

    /** What a caller hears, of which there are two and not three. */
    public enum Answer {
        /** The category the command's own row declares for a platform that declined. */
        DECLARED_REJECTION,
        /** The category for a platform that said nothing, which claims nothing about its state. */
        OUTCOME_UNKNOWN
    }

    /** Every platform interface this agent calls, which is where a fault is injected. */
    public enum Service {

        /** The job system, which runs deferred work. */
        JOB_SYSTEM("job_system"),

        /** The workflow engine, which starts and suspends instances. */
        WORKFLOW_ENGINE("workflow_engine"),

        /** Replication, which is offered content rather than told to publish it. */
        REPLICATION("replication"),

        /** User management, which answers about principals and their membership. */
        USER_MANAGEMENT("user_management"),

        /** Configuration, which answers what exists and sometimes what it holds. */
        CONFIGURATION("configuration");

        private final String spelling;

        Service(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this service is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The service one spelling names.
         *
         * @param spelling the spelling
         * @return the service, or nothing where no such service is enumerated
         */
        public static Optional<Service> named(String spelling) {
            return Arrays.stream(values())
                    .filter(service -> service.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * One injection: a fault, on a service.
     *
     * @param fault what the service does instead of answering
     * @param service which service does it
     */
    public record Injection(Fault fault, Service service) {

        /**
         * How this injection is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return fault.spelling() + "@" + service.spelling();
        }
    }

    private PlatformFaultInjector() {
    }

    /**
     * Every injection this suite runs: all three faults on every service.
     *
     * @return the injections, in a stable order
     */
    public static List<Injection> everyInjection() {
        return Arrays.stream(Service.values())
                .flatMap(service -> Arrays.stream(Fault.values())
                        .map(fault -> new Injection(fault, service)))
                .toList();
    }

    /**
     * Whether the unknown outcome is reachable at all, which is what makes it a category.
     *
     * @return whether some fault produces it
     */
    public static boolean unknownOutcomeIsReachable() {
        return Arrays.stream(Fault.values())
                .anyMatch(fault -> fault.answer() == Answer.OUTCOME_UNKNOWN);
    }

    /**
     * Whether a rejection and a throw stay indistinguishable to a caller.
     *
     * <p>Deliberate rather than accidental: a caller who could tell them apart would be reading
     * this product's opinion of somebody else's exception, and acting on it.</p>
     *
     * @return whether both produce the same answer
     */
    public static boolean aRejectionAndAThrowLookAlike() {
        return Fault.REJECTS.answer() == Fault.THROWS.answer();
    }
}
