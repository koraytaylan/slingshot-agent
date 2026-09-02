// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.List;

/**
 * What a replay produced: what a subscriber has not seen, a resynchronisation, or a refusal.
 *
 * <p>The three are separated because a subscriber does different things with each. Served events
 * are appended to what it already knows. A reset means what it knows is no longer connected to what
 * this side holds, so it takes the snapshot and starts from there rather than believing it is
 * merely behind. A refusal means the question was not one this store can answer at all, and
 * answering it with an empty list would be indistinguishable, to the subscriber, from having caught
 * up.</p>
 */
public sealed interface ReplayOutcome
        permits ReplayOutcome.Served, ReplayOutcome.Reset, ReplayOutcome.Refused {

    /** Why a replay was refused outright. */
    enum Refusal {
        /** The cursor belongs to an incarnation this store is not serving. */
        FOREIGN_GENERATION,
        /** There is no operation to replay, so there is nothing this cursor is about. */
        NO_OPERATION
    }

    /**
     * The events a subscriber has not been shown, oldest first, with what is currently true.
     *
     * @param current what is true now, so a reader that wants only that does not have to fold
     * @param events the events after the cursor, as the bytes they were written as
     */
    record Served(SnapshotStore.Materialised current, List<String> events)
            implements ReplayOutcome {

        /** Holds a list nothing can change afterwards. */
        public Served {
            events = List.copyOf(events);
        }
    }

    /**
     * That the cursor cannot be honoured, with what to start again from.
     *
     * @param current what is true now, which is what the subscriber resynchronises from
     * @param detail what was observed, so a subscriber's operator can see why it happened
     */
    record Reset(SnapshotStore.Materialised current, String detail) implements ReplayOutcome {
    }

    /**
     * That the question is not one this store answers.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    record Refused(Refusal refusal, String detail) implements ReplayOutcome {
    }
}
