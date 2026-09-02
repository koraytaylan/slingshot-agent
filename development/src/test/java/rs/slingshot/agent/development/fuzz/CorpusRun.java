// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import rs.slingshot.agent.development.FuzzTargetInventory;
import rs.slingshot.agent.development.RepositoryTree;

/**
 * One target driven over its committed corpus and a declared number of mutations of it.
 *
 * <p>The corpus is the part that runs on every build, and it is what makes a fixed defect stay
 * fixed: every input that has ever produced a finding is in it permanently, so a reintroduced
 * defect is caught without a single new iteration. The mutations are the part that finds something
 * new, and they are seeded, so two people running this run the same thing.</p>
 *
 * <p>The coverage-guided tool runs the same targets over the same corpus for very much longer, and
 * is pinned and verified separately. This is not a substitute for it — it is the half that costs
 * nothing and therefore never stops running.</p>
 */
final class CorpusRun {

    /** The seed every mutation here comes from, so a finding is reproducible. */
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

    /** How many mutations of the corpus one target is driven over on an ordinary build. */
    static final int MUTATIONS = 512;

    private CorpusRun() {
    }

    /**
     * Every input one target holds, read from its committed corpus.
     *
     * @param name the target's declared name
     * @return the inputs, in the order the corpus holds them
     */
    static List<byte[]> corpusOf(String name) {
        final Path root = RepositoryTree.locate();
        final FuzzTargetInventory inventory =
                ((FuzzTargetInventory.Loaded) FuzzTargetInventory.read(root)).inventory();
        final FuzzTargetInventory.TargetRow target = inventory.targets().stream()
                .filter(row -> row.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " is not a declared target"));
        return FuzzTargetInventory.corpusOf(root, target).stream()
                .map(CorpusRun::bytesOf)
                .toList();
    }

    /**
     * Everything one target broke over its corpus and a seeded run of mutations of it.
     *
     * @param name the target's declared name
     * @param target the target itself
     * @return one entry per input that broke a property, naming the property and the input
     */
    static List<String> findings(String name, FuzzTarget target) {
        final List<byte[]> corpus = corpusOf(name);
        final List<String> findings = new ArrayList<>();
        for (int index = 0; index < corpus.size(); index++) {
            record(findings, target, corpus.get(index), name + " corpus entry " + index);
        }
        final RandomGenerator mutating = SEEDED.create(SEED);
        for (int iteration = 0; iteration < MUTATIONS && !corpus.isEmpty(); iteration++) {
            final byte[] mutated = mutate(corpus.get(mutating.nextInt(corpus.size())), mutating);
            record(findings, target, mutated, name + " mutation " + iteration);
        }
        return findings;
    }

    private static void record(List<String> findings, FuzzTarget target, byte[] input,
                               String where) {
        final Attempted.Answered<FuzzOutcome> asked = Attempted.of(() -> target.of(input));
        if (asked.threw()) {
            findings.add(where + " threw " + asked.threwWhat());
            return;
        }
        if (asked.value().orElseThrow() instanceof final FuzzOutcome.Broken broken) {
            findings.add(where + " broke \"" + broken.property() + "\": " + broken.detail());
        }
    }

    /**
     * One mutation of one input: a byte changed, a byte removed, or a byte inserted.
     *
     * <p>Three kinds because they are the three ways a value goes wrong on a wire, and because a
     * mutator that only flipped bytes would never produce a truncated document — which is the shape
     * that finds a reader reporting what it read so far.</p>
     *
     * @param input what to mutate
     * @param mutating the seeded generator
     * @return the mutated bytes
     */
    private static byte[] mutate(byte[] input, RandomGenerator mutating) {
        if (input.length == 0) {
            return new byte[] {(byte) mutating.nextInt()};
        }
        final int at = mutating.nextInt(input.length);
        return switch (mutating.nextInt(3)) {
            case 0 -> changed(input, at, (byte) mutating.nextInt());
            case 1 -> removed(input, at);
            default -> inserted(input, at, (byte) mutating.nextInt());
        };
    }

    private static byte[] changed(byte[] input, int at, byte value) {
        final byte[] mutated = input.clone();
        mutated[at] = value;
        return mutated;
    }

    private static byte[] removed(byte[] input, int at) {
        final byte[] mutated = new byte[input.length - 1];
        System.arraycopy(input, 0, mutated, 0, at);
        System.arraycopy(input, at + 1, mutated, at, input.length - at - 1);
        return mutated;
    }

    private static byte[] inserted(byte[] input, int at, byte value) {
        final byte[] mutated = new byte[input.length + 1];
        System.arraycopy(input, 0, mutated, 0, at);
        mutated[at] = value;
        System.arraycopy(input, at, mutated, at + 1, input.length - at);
        return mutated;
    }

    private static byte[] bytesOf(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
