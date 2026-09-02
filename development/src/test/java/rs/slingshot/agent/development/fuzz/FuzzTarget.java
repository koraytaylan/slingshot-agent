// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

/**
 * What every fuzz target is: one property, asked of arbitrary bytes.
 *
 * <p>An interface rather than a base class because there is nothing to share. Each target decides
 * what its property is, and the only thing they have in common is that neither an accepted input
 * nor a refused one is a failure — a target that treated a refusal as a finding would report the
 * bounded reader doing its job.</p>
 */
@FunctionalInterface
public interface FuzzTarget {

    /**
     * Asks the property of one input.
     *
     * @param input arbitrary bytes, which may be anything at all
     * @return whether the property held, and what broke it where it did not
     */
    FuzzOutcome of(byte[] input);
}
