// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.nio.charset.StandardCharsets;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * One physical delivery of one logical operation: which job it was, which node saw it, and when.
 *
 * <p>Sling delivers a job at least once, a cluster can move it, and a node can stop holding one. A
 * duplicate physical record is not a defect to be prevented; it is the normal case, and what this
 * type is for is making it harmless and visible — an operation that was delivered four times is one
 * fact about a job system, not four pieces of work.</p>
 *
 * @param jobIdentifier the platform's own name for this delivery
 * @param observedBy the node that received it
 * @param observedAtUnixMilliseconds when that node received it
 */
public record PhysicalAttempt(String jobIdentifier, String observedBy,
                              long observedAtUnixMilliseconds) {

    /** Why something is not an attempt this store will record. */
    public enum Refusal {
        /** The job identifier is longer than the platform's own bound for one. */
        IDENTIFIER_TOO_LONG,
        /** The job identifier is empty, which names no delivery. */
        IDENTIFIER_EMPTY,
        /** The node that observed it is not named. */
        OBSERVER_EMPTY
    }

    /** The result of holding one: the attempt, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A delivery this store will record.
     *
     * @param attempt the attempt
     */
    public record Held(PhysicalAttempt attempt) implements Outcome {
    }

    /**
     * Something that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Holds one delivery, if it is one this store can record.
     *
     * @param jobIdentifier the platform's own name for the delivery
     * @param observedBy the node that received it
     * @param observedAtUnixMilliseconds when that node received it
     * @param contract the authenticated contract, which declares the identifier bound
     * @return the attempt, or the one reason there is none
     */
    public static Outcome of(String jobIdentifier, String observedBy,
                             long observedAtUnixMilliseconds, AgentContract contract) {
        final int length = jobIdentifier.getBytes(StandardCharsets.UTF_8).length;
        final long bound = contract.value(ContractLimit.TRANSPORT_MAXIMUM_SLING_JOB_IDENTIFIER_BYTES);
        if (length == 0) {
            return new Refused(Refusal.IDENTIFIER_EMPTY, "a delivery with no name is one nothing"
                    + " can be told apart from another delivery");
        }
        if (length > bound) {
            return new Refused(Refusal.IDENTIFIER_TOO_LONG,
                    length + " bytes is past the platform's own bound of " + bound);
        }
        if (observedBy.isEmpty()) {
            return new Refused(Refusal.OBSERVER_EMPTY,
                    "an attempt nobody observed is a fact about nothing");
        }
        return new Held(new PhysicalAttempt(jobIdentifier, observedBy,
                observedAtUnixMilliseconds));
    }
}
