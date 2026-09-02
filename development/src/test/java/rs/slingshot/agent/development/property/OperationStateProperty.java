// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.property;

import java.util.ArrayList;
import java.util.List;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * The first invariant: an operation reaches a terminal state once, and from a state somebody read.
 *
 * <p>The kinds and their finality are the product's own — read from {@link JobEventKind} rather
 * than written down here — so a build that added a sixth kind or changed which ones end a job
 * changes what this property is about. A model with its own copy of the table would go on holding
 * a property about a machine that no longer exists.</p>
 *
 * <p>Two halves. Nothing reaches a terminal state twice with different outcomes, because a caller
 * told an operation succeeded and then that it failed has no way to decide which is true. And every
 * transition is from the state the actor read, because a transition applied to a state somebody
 * assumed is the shape of every lost update there has ever been.</p>
 */
final class OperationStateProperty {

    private OperationStateProperty() {
    }

    /** What one actor tried to do, and what it had read when it decided to. */
    record Step(JobEventKind kind, JobEventKind readAs) {
    }

    /** What the machine holds after a sequence: the last kind applied, or nothing yet. */
    record State(JobEventKind kind, boolean started) {

        /** Nothing has happened yet. */
        static State initial() {
            return new State(JobEventKind.ACCEPTED, false);
        }
    }

    /**
     * Every step a generated sequence may take.
     *
     * @return the alphabet, derived from the product's own kinds
     */
    static List<Step> alphabet() {
        final List<Step> steps = new ArrayList<>();
        for (final JobEventKind kind : JobEventKind.values()) {
            for (final JobEventKind readAs : JobEventKind.values()) {
                steps.add(new Step(kind, readAs));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * Whether the invariant holds over one sequence, applied the way the store applies one.
     *
     * @param sequence what the actors tried
     * @return whether it held
     */
    static boolean holds(List<Step> sequence) {
        return holds(sequence, Fidelity.AS_BUILT);
    }

    /** Which transition table the model applies, so a broken one can be shown to be found. */
    enum Fidelity {
        /** The one the product has. */
        AS_BUILT,
        /** One that lets a terminal state be left, which is the defect worth catching. */
        TERMINAL_IS_NOT_FINAL
    }

    /**
     * Whether the invariant holds, under a named transition table.
     *
     * @param sequence what the actors tried
     * @param fidelity which table to apply
     * @return whether it held
     */
    static boolean holds(List<Step> sequence, Fidelity fidelity) {
        State state = State.initial();
        JobEventKind terminal = null;
        for (final Step step : sequence) {
            if (state.started() && step.readAs() != state.kind()) {
                continue;
            }
            if (terminal != null && fidelity == Fidelity.AS_BUILT) {
                continue;
            }
            if (terminal != null && terminal != step.kind()) {
                return false;
            }
            state = new State(step.kind(), true);
            if (step.kind().finality() == JobEventKind.Finality.ENDS) {
                terminal = step.kind();
            }
        }
        return true;
    }
}
