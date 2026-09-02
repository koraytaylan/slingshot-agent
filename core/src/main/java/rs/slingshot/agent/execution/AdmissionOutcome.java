// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

/**
 * What this side did with a submission: took it, recognised it, or refused it as another piece of
 * work wearing the same name.
 *
 * <p>Three answers and no fourth. A conflicting submission neither overwrites the record nor
 * executes: an identifier reused against another target, another revision of a caller's
 * environment, or another command is a different piece of work, and silently answering it from the
 * first one's record would be this side deciding that the difference did not matter.</p>
 */
public sealed interface AdmissionOutcome
        permits AdmissionOutcome.Accepted, AdmissionOutcome.Recognised, AdmissionOutcome.Conflicting,
                AdmissionOutcome.Refused {

    /**
     * A submission nothing had recorded, now recorded.
     *
     * @param operation the record this side wrote
     */
    record Accepted(LogicalOperation operation) implements AdmissionOutcome {
    }

    /**
     * A submission this side has already recorded, answered from the record rather than run again.
     *
     * @param operation the record it was recognised as
     */
    record Recognised(LogicalOperation operation) implements AdmissionOutcome {
    }

    /**
     * A submission that means something else under a name this side has already recorded.
     *
     * @param member which member disagreed
     * @param detail what the record holds and what arrived, without echoing either in full
     */
    record Conflicting(String member, String detail) implements AdmissionOutcome {
    }

    /**
     * A submission this side will not consider at all.
     *
     * @param refusal why it will not
     * @param detail what was observed
     */
    record Refused(Reason refusal, String detail) implements AdmissionOutcome {
    }

    /** Why a submission is not considered. */
    enum Reason {
        /** It names an incarnation of the store that this one has served and no longer does. */
        RETAINED_GENERATION,
        /** It names an incarnation this store has never served. */
        UNKNOWN_GENERATION,
        /** The instant its client says it began is one this side's own clock cannot believe. */
        UNBELIEVABLE_REQUEST_START,
        /** The store could not be written, which is a different thing from a refusal. */
        NOT_RECORDED
    }
}
