// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.property;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Seeded sequences, and the shrinking that turns a counterexample into something readable.
 *
 * <p>Generated from a recorded seed rather than from the clock, because a property that fails on
 * Tuesday and passes on Wednesday is a property nobody trusts. Every failure here is reproducible
 * by somebody who was not there, from the seed printed beside it.</p>
 *
 * <p>Shrinking matters more than generation does. A four-hundred-step counterexample says a
 * property is broken; a three-step one says how — and the three-step one is what gets written down
 * as a permanent case, so a defect once found is a test rather than a memory.</p>
 */
final class Generated {

    /** The seed every sequence here comes from, recorded so a failure is reproducible. */
    static final long SEED = 20260902;

    /**
     * The generator every sequence here comes from, named rather than left to the runtime.
     *
     * <p>Predictable on purpose, which is the whole point: a finding nobody can reproduce is a
     * finding nobody can fix. Nothing here is a secret, a token, or a key — it is an input to a
     * suite, and the suite is worth less the less predictable it is.</p>
     */
    private static final RandomGeneratorFactory<RandomGenerator> SEEDED =
            RandomGeneratorFactory.of("L64X128MixRandom");

    /** How many sequences one property is asked over on an ordinary build. */
    static final int SEQUENCES = 256;

    /** The longest sequence generated, which is long enough to interleave and short enough to read. */
    static final int LONGEST = 24;

    private Generated() {
    }

    /**
     * Every generated sequence over one alphabet, at the recorded seed.
     *
     * @param alphabet what a step may be
     * @param <T> what a step is
     * @return the sequences, in the order they were generated
     */
    static <T> List<List<T>> sequences(List<T> alphabet) {
        final RandomGenerator generating = SEEDED.create(SEED);
        final List<List<T>> sequences = new ArrayList<>();
        for (int index = 0; index < SEQUENCES; index++) {
            final int length = generating.nextInt(LONGEST) + 1;
            final List<T> sequence = new ArrayList<>();
            for (int step = 0; step < length; step++) {
                sequence.add(alphabet.get(generating.nextInt(alphabet.size())));
            }
            sequences.add(List.copyOf(sequence));
        }
        return List.copyOf(sequences);
    }

    /**
     * The shortest sequence that still breaks a property, found by removing steps one at a time.
     *
     * <p>Removing rather than halving, because the interesting counterexamples here are short
     * already and the step that matters is usually one somebody can point at.</p>
     *
     * @param sequence a sequence the property does not hold for
     * @param holds whether the property holds for a sequence
     * @param <T> what a step is
     * @return the shortest sequence it still does not hold for
     */
    static <T> List<T> shrunk(List<T> sequence, Predicate<List<T>> holds) {
        List<T> shortest = sequence;
        boolean shrinking = true;
        while (shrinking) {
            shrinking = false;
            for (int at = 0; at < shortest.size(); at++) {
                final List<T> without = new ArrayList<>(shortest);
                without.remove(at);
                if (!without.isEmpty() && !holds.test(without)) {
                    shortest = List.copyOf(without);
                    shrinking = true;
                    break;
                }
            }
        }
        return shortest;
    }

    /**
     * The first sequence a property does not hold for, shrunk, or nothing where it holds for all.
     *
     * @param sequences what to ask over
     * @param holds whether the property holds for one
     * @param <T> what a step is
     * @return the shrunk counterexample, or an empty list where there is none
     */
    static <T> List<T> counterexample(List<List<T>> sequences, Predicate<List<T>> holds) {
        return sequences.stream()
                .filter(sequence -> !holds.test(sequence))
                .findFirst()
                .map(sequence -> shrunk(sequence, holds))
                .orElse(List.of());
    }
}
