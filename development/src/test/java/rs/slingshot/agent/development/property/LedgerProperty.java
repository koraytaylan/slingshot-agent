// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.property;

import java.util.ArrayList;
import java.util.List;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * The third and fourth invariants: the snapshot is the fold, and the counters are the contents.
 *
 * <p>The snapshot exists so nobody has to replay a ledger to answer a question. That is only safe
 * while the snapshot is exactly what replaying would produce — the moment the two can differ, the
 * cheap answer and the true answer are different answers and nothing says which one a caller
 * got.</p>
 *
 * <p>The other half is about the pair that has to land together: a record that is terminal without
 * its terminal event, or an event that is terminal without its record, is a caller told an
 * operation ended by one surface and told it is running by another. And a counter that disagrees
 * with what the store holds is capacity refused for work that is not there, or admitted for work
 * that is.</p>
 */
final class LedgerProperty {

    private LedgerProperty() {
    }

    /** What one step did to the pair. */
    enum Act {
        /** Appended one event and materialised the snapshot in the same commit. */
        APPEND,
        /** Admitted one unit of capacity for a caller. */
        ADMIT,
        /** Released one. */
        RELEASE,
        /** Appended a terminal event, which is the one that has to land with its record. */
        END
    }

    /**
     * One step, and which caller it was for.
     *
     * @param act what happened
     * @param caller whose capacity it counted against
     */
    record Step(Act act, int caller) {
    }

    /** How many callers the generated interleavings run over. */
    private static final int CALLERS = 2;

    /**
     * Every step a generated sequence may take.
     *
     * @return the alphabet
     */
    static List<Step> alphabet() {
        final List<Step> steps = new ArrayList<>();
        for (int caller = 0; caller < CALLERS; caller++) {
            for (final Act act : Act.values()) {
                steps.add(new Step(act, caller));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * Whether both invariants hold over one sequence.
     *
     * @param sequence what happened
     * @return whether they held
     */
    static boolean holds(List<Step> sequence) {
        return holds(sequence, Fidelity.AS_BUILT);
    }

    /** Which materialisation the model applies, so a broken one can be shown to be found. */
    enum Fidelity {
        /** The one the product has: the event and the snapshot land in one commit. */
        AS_BUILT,
        /** One that materialises separately, which is the defect worth catching. */
        SNAPSHOT_IN_A_SECOND_COMMIT,
        /** One whose counter is advanced without the contents, which is the other. */
        COUNTER_WITHOUT_CONTENTS
    }

    /**
     * Whether both invariants hold, under a named materialisation.
     *
     * @param sequence what happened
     * @param fidelity which materialisation to apply
     * @return whether they held
     */
    static boolean holds(List<Step> sequence, Fidelity fidelity) {
        final List<JobEventKind> ledger = new ArrayList<>();
        final long[] held = new long[CALLERS];
        long snapshot = 0;
        long total = 0;
        boolean recordTerminal = false;
        for (final Step step : sequence) {
            if (step.act() == Act.APPEND) {
                ledger.add(JobEventKind.PROGRESS);
            }
            if (step.act() == Act.END && !recordTerminal) {
                ledger.add(JobEventKind.SUCCEEDED);
                recordTerminal = true;
            }
            if (fidelity != Fidelity.SNAPSHOT_IN_A_SECOND_COMMIT) {
                snapshot = ledger.size();
            }
            if (step.act() == Act.ADMIT) {
                held[step.caller()] = held[step.caller()] + 1;
                total = fidelity == Fidelity.COUNTER_WITHOUT_CONTENTS ? total + 2 : total + 1;
            }
            if (step.act() == Act.RELEASE && held[step.caller()] > 0) {
                held[step.caller()] = held[step.caller()] - 1;
                total = total - 1;
            }
            if (snapshot != ledger.size()) {
                return false;
            }
            if (recordTerminal != ledger.contains(JobEventKind.SUCCEEDED)) {
                return false;
            }
            if (total != held[0] + held[1]) {
                return false;
            }
        }
        return true;
    }
}
