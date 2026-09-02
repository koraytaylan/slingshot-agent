// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * What has happened to one logical operation, as a closed set with its transitions declared.
 *
 * <p>Every legal transition is data rather than a chain of conditions: a matrix can be read, and a
 * chain of conditions is a place where the fourth case is the one nobody wrote. Terminality is
 * intrinsic to the state for the same reason it is intrinsic to an event kind — nothing can then
 * describe a state as terminal in one place and not in another.</p>
 */
public enum OperationState {

    /** The submission was taken and a record exists for it, and nothing has started it. */
    ACCEPTED("accepted", JobEventKind.ACCEPTED),

    /** A worker holds the fence and is running it. */
    RUNNING("running", JobEventKind.STARTED),

    /** It finished and did what it was asked. */
    SUCCEEDED("succeeded", JobEventKind.SUCCEEDED),

    /** It finished and did not. */
    FAILED("failed", JobEventKind.FAILED);

    private final String spelling;
    private final JobEventKind kind;

    OperationState(String spelling, JobEventKind kind) {
        this.spelling = spelling;
        this.kind = kind;
    }

    /** Every transition this build permits, from one state to another. */
    private static final List<List<OperationState>> TRANSITIONS = List.of(
            List.of(ACCEPTED, RUNNING),
            List.of(ACCEPTED, FAILED),
            List.of(RUNNING, SUCCEEDED),
            List.of(RUNNING, FAILED));

    /**
     * How this state is spelled where it is written down.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The event kind that says a job reached this state.
     *
     * @return the kind, which is the wire vocabulary's own
     */
    public JobEventKind kind() {
        return kind;
    }

    /**
     * Whether anything else can happen to an operation in this state.
     *
     * @return the finality, which is the event kind's own
     */
    public JobEventKind.Finality finality() {
        return kind.finality();
    }

    /**
     * Whether one state may follow this one.
     *
     * <p>A repeat of a terminal state is permitted and changes nothing: a worker that committed a
     * terminal transition and then lost its answer has to be able to say the same thing again.</p>
     *
     * @param next the state being moved to
     * @return whether the move is one this build permits
     */
    public boolean permits(OperationState next) {
        return this == next && finality() == JobEventKind.Finality.ENDS
                || TRANSITIONS.contains(List.of(this, next));
    }

    /**
     * Every transition this build permits, as pairs.
     *
     * @return the transitions, in the order they are declared
     */
    public static List<List<OperationState>> transitions() {
        return TRANSITIONS;
    }

    /**
     * The state one spelling names.
     *
     * @param spelling the spelling
     * @return the state, or nothing where this build knows no such state
     */
    public static Optional<OperationState> named(String spelling) {
        return Arrays.stream(values())
                .filter(state -> state.spelling.equals(spelling))
                .findFirst();
    }
}
