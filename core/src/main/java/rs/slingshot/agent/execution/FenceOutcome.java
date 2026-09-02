// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

/**
 * What happened when a worker asked for, kept, or lost the right to execute.
 *
 * <p>Lost, expired, and contended are three answers rather than one, because they mean three
 * different things to whoever reads the record afterwards: somebody else holds it now, this
 * worker's own hold ran out while it was working, or the store was busy and nothing was decided.
 * A single "no" would leave an operator unable to tell a handover from a pause.</p>
 */
public sealed interface FenceOutcome permits FenceOutcome.Held, FenceOutcome.Refused,
        FenceOutcome.Lost, FenceOutcome.Contended {

    /**
     * The fence is this worker's until it expires.
     *
     * @param holder who holds it and until when
     */
    record Held(FenceHolder holder) implements FenceOutcome {
    }

    /**
     * Somebody else holds it, and their hold has not run out.
     *
     * @param holder who holds it and until when
     */
    record Refused(FenceHolder holder) implements FenceOutcome {
    }

    /**
     * This worker's own hold is gone: somebody else has taken it, or it ran out.
     *
     * @param detail what was observed, naming who holds it now
     */
    record Lost(String detail) implements FenceOutcome {
    }

    /**
     * Nothing was decided, because the store was being written by somebody else.
     *
     * @param detail what was observed
     */
    record Contended(String detail) implements FenceOutcome {
    }
}
