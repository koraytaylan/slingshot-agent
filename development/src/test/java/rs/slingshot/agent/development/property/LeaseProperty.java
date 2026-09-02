// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.property;

import java.util.List;

/**
 * The second invariant: two workers, one lease, and no interleaving where both are holding it.
 *
 * <p>Two halves, and the second is the one that costs somebody a repository. No interleaving leaves
 * both workers holding the lease — that one is obvious. And no worker writes after losing it, which
 * is not: a worker that lost a lease while it was part-way through something goes on believing it
 * holds one until it next looks, and what it does in between is a write nobody fenced.</p>
 *
 * <p>The fence is a number rather than a flag. A worker writes under the fence it took, and a write
 * under a fence lower than the one the lease has moved to is refused — which is what makes losing a
 * lease something the store notices rather than something the worker has to.</p>
 */
final class LeaseProperty {

    private LeaseProperty() {
    }

    /** What one of the two workers did. */
    enum Act {
        /** Took the lease, if it was free or expired. */
        TAKE,
        /** Renewed it, if it still holds it. */
        RENEW,
        /** Wrote something under the fence it last took. */
        WRITE,
        /** Let time pass, which is what expires a lease nobody renewed. */
        WAIT
    }

    /**
     * One step: which worker, and what it did.
     *
     * @param worker which of the two
     * @param act what it did
     */
    record Step(int worker, Act act) {
    }

    /** How long a lease is held for, in the same units the steps advance. */
    private static final long HELD_FOR = 3;

    /** Which fence a worker holds when it holds none. */
    private static final long NO_FENCE = 0;

    /**
     * Every step a generated sequence may take, over two workers.
     *
     * @return the alphabet
     */
    static List<Step> alphabet() {
        return List.of(new Step(0, Act.TAKE), new Step(0, Act.RENEW), new Step(0, Act.WRITE),
                new Step(1, Act.TAKE), new Step(1, Act.RENEW), new Step(1, Act.WRITE),
                new Step(0, Act.WAIT));
    }

    /**
     * Whether the invariant holds over one interleaving.
     *
     * @param sequence what the two workers did
     * @return whether it held
     */
    static boolean holds(List<Step> sequence) {
        return holds(sequence, Fidelity.AS_BUILT);
    }

    /** Which lease comparison the model applies, so a broken one can be shown to be found. */
    enum Fidelity {
        /** The one the product has: a write is fenced by the number it was taken under. */
        AS_BUILT,
        /** One that compares holders rather than fences, which is the defect worth catching. */
        HOLDER_RATHER_THAN_FENCE
    }

    /**
     * Whether the invariant holds, under a named comparison.
     *
     * @param sequence what the two workers did
     * @param fidelity which comparison to apply
     * @return whether it held
     */
    static boolean holds(List<Step> sequence, Fidelity fidelity) {
        long now = 0;
        long fence = NO_FENCE;
        long heldUntil = 0;
        int holder = -1;
        final long[] taken = {NO_FENCE, NO_FENCE};
        final boolean[] believes = {false, false};
        for (final Step step : sequence) {
            if (step.act() == Act.WAIT) {
                now = now + 1;
            }
            if (step.act() == Act.TAKE && (holder < 0 || now >= heldUntil)) {
                fence = fence + 1;
                holder = step.worker();
                heldUntil = now + HELD_FOR;
                taken[step.worker()] = fence;
                believes[step.worker()] = true;
            }
            if (step.act() == Act.RENEW && taken[step.worker()] == fence && fence != NO_FENCE) {
                heldUntil = now + HELD_FOR;
            }
            // As built, the store decides: a write carries the fence it was taken under and one
            // below the lease's own is refused. The broken comparison lets the worker decide, and a
            // worker that lost a lease mid-way goes on believing it holds one until it next looks,
            // which is the whole reason the fence is a number.
            final boolean permitted = fidelity == Fidelity.AS_BUILT
                    ? taken[step.worker()] == fence && fence != NO_FENCE
                    : believes[step.worker()];
            if (step.act() == Act.WRITE && permitted && taken[step.worker()] != fence) {
                return false;
            }
            if (taken[0] == fence && taken[1] == fence && fence != NO_FENCE) {
                return false;
            }
        }
        return true;
    }
}
