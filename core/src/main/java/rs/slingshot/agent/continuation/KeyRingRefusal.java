// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

/**
 * Why a key ring could not be read, written, or rotated.
 *
 * @param failure what was wrong
 * @param detail what was observed, naming the instant or the bound the failure is about
 */
public record KeyRingRefusal(Failure failure, String detail) {

    /** What can be wrong with a key ring or with a change to one. */
    public enum Failure {
        /**
         * The deployment holds no key ring.
         *
         * <p>Not the same as an empty one. A caller finding an empty ring would issue keys into it;
         * one finding nothing is told to look at why, because a ring that does not exist is a
         * deployment that was never prepared.</p>
         */
        ABSENT,
        /** One key is longer than the bound the contract declares. */
        KEY_TOO_LONG,
        /** The whole record is longer than the bound the contract declares. */
        RECORD_TOO_LONG,
        /** A rotation was asked for while the previous key is still retained. */
        PRIOR_STILL_RETAINED,
        /** The ring changed since the caller read it, so the write it asked for is not the one it
         * meant. */
        CHANGED_SINCE_IT_WAS_READ,
        /** The caller does not hold the lease, so nothing it writes could be linearizable. */
        NOT_THE_LEASE_HOLDER
    }

    /**
     * Renders the refusal the way a failure message states one.
     *
     * @return the rendering
     */
    public String rendered() {
        return failure + ": " + detail;
    }
}
