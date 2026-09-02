// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

/**
 * What one input did to a target: held its property, or broke it and said how.
 *
 * <p>A target that threw would be a target reporting a defect in itself the same way it reports a
 * defect in what it is fuzzing. So every target answers rather than throws, and the answer names
 * the property that did not hold — which is the sentence somebody reads first when a corpus entry
 * starts failing two years from now.</p>
 */
public sealed interface FuzzOutcome permits FuzzOutcome.Held, FuzzOutcome.Broken {

    /** An input the property held for, whether it was accepted or refused. */
    record Held() implements FuzzOutcome {
    }

    /**
     * One it did not.
     *
     * @param property the property that did not hold, named as a person would say it
     * @param detail what happened instead
     */
    record Broken(String property, String detail) implements FuzzOutcome {
    }

    /**
     * A held outcome, which is what nearly every input produces.
     *
     * @return the outcome
     */
    static FuzzOutcome held() {
        return new Held();
    }

    /**
     * A broken one.
     *
     * @param property the property that did not hold
     * @param detail what happened instead
     * @return the outcome
     */
    static FuzzOutcome broken(String property, String detail) {
        return new Broken(property, detail);
    }
}
