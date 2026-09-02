// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

/**
 * What a conditional write did, as one of five things and never as an ambiguity.
 *
 * <p>Claiming a path somebody else has already claimed is the ordinary case this design is built
 * around rather than a failure: two workers racing for one operation is exactly what
 * claim-by-creation is for, and the one that loses has learnt that the operation exists, which is
 * what it wanted to know. A caller that treated it as an error would report a defect every time the
 * design worked.</p>
 */
public enum WriteOutcome {

    /** The path was free and is now this writer's. */
    CLAIMED,

    /** The path was already held, which is the ordinary answer rather than a failure. */
    ALREADY_HELD,

    /** The value was what the caller expected and is now what the caller asked for. */
    WRITTEN,

    /** The value was not what the caller expected, so nothing was written. */
    VALUE_CHANGED,

    /** Somebody else was writing the same thing often enough that this writer gave up trying. */
    CONTENDED
}
