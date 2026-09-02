// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * The one topic a deferred command travels on, and the queue values a deployment configures for it.
 *
 * <p>The job system's role is smaller than it looks. A command that finishes inside its own request
 * does not need one at all, and every command this product ships is one of those — so this exists
 * for the deferred class, carries no shipped command today, and is proved by a fake one. What it
 * carries is a physical attempt to a node that can execute it, and that is all: it is not the
 * record of the work, it is not the idempotency mechanism, and its retry policy is not the
 * operation's.</p>
 */
public final class CommandJobTopic {

    /** The one topic this agent consumes, spelled once. */
    public static final String TOPIC = "rs/slingshot/agent/command";

    /** The job property an operation's own name travels in. */
    public static final String IDENTIFIER = "agent_operation_identifier";

    /** The job property the store's incarnation travels in. */
    public static final String GENERATION = "agent_event_store_generation";

    /** The job property the target this work is against travels in. */
    public static final String TARGET_DIGEST = "author_target_identity_digest";

    /** The job property the environment revision travels in. */
    public static final String ENVIRONMENT_REVISION = "selected_environment_revision";

    /** The job property the execution class travels in. */
    public static final String EXECUTION_CLASS = "execution_class";

    /** Every property a job carries, and there is no sixth. */
    public static final List<String> PROPERTIES = List.of(IDENTIFIER, GENERATION, TARGET_DIGEST,
            ENVIRONMENT_REVISION, EXECUTION_CLASS);

    /**
     * The wire name of every command this build defers, which is none of them.
     *
     * <p>It is a list rather than a sentence so that the day a long-running command arrives, the
     * change is one row here and the queue configuration a deployment already has. An empty list is
     * what makes the queue provably empty across a workload of everything this product ships.</p>
     */
    public static final List<String> DEFERRED_COMMANDS = List.of();

    private CommandJobTopic() {
    }

    /**
     * How one command runs, decided by its wire name and nothing about the caller.
     *
     * @param wireName the command's wire name
     * @return deferred where this build defers it, immediate otherwise
     */
    public static ExecutionClass executionClassOf(String wireName) {
        return DEFERRED_COMMANDS.contains(wireName)
                ? ExecutionClass.DEFERRED
                : ExecutionClass.IMMEDIATE;
    }

    /**
     * Whether a command runs inside the request that submitted it, or later.
     *
     * <p>Every command this product ships is immediate, and an immediate command never reaches a
     * queue: it is executed by the route that admitted it, on that caller's own session. The
     * deferred class exists so that the durable half of this product is designed now rather than at
     * the moment somebody is in a hurry.</p>
     */
    public enum ExecutionClass {

        /** Executed inside the request that submitted it, and never enqueued. */
        IMMEDIATE("immediate"),

        /** Executed later, by a worker that took the fence. */
        DEFERRED("deferred");

        private final String spelling;

        ExecutionClass(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this class is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The class one spelling names.
         *
         * @param spelling the spelling
         * @return the class, or nothing where this build knows no such class
         */
        public static Optional<ExecutionClass> named(String spelling) {
            return Arrays.stream(values())
                    .filter(executionClass -> executionClass.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * What a deployment configures the queue with, every value read from the contract.
     *
     * @param maximumParallel how many of these jobs may run at once
     * @param maximumRetries how many times a job is retried before the job system gives up
     * @param retryDelayMilliseconds how long the job system waits before retrying one
     */
    public record QueueValues(long maximumParallel, long maximumRetries,
                              long retryDelayMilliseconds) {

        /**
         * The values the contract declares.
         *
         * @param contract the authenticated contract
         * @return the values
         */
        public static QueueValues from(AgentContract contract) {
            return new QueueValues(
                    contract.value(ContractLimit.MAXIMUM_CONCURRENT_COMMAND_EXECUTIONS),
                    contract.value(ContractLimit.MAXIMUM_AUTOMATIC_RETRY_ATTEMPTS),
                    contract.value(ContractLimit.RETRY_BASE_MILLISECONDS));
        }
    }
}
