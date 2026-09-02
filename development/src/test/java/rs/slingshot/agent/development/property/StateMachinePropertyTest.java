// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * Four sentences carry the entire one-effect argument, asked over generated sequences.
 *
 * <p>A generated counterexample to any of them is worth more than another example that passes,
 * which is what these are for. Every one is seeded and reproducible, every counterexample is shrunk
 * to something a person can read, and the shrunk ones are recorded as permanent cases that run
 * without generation — so a defect once found costs nothing and never stops being checked.</p>
 *
 * <p>The properties are asked of models rather than of the running store, and the models take their
 * vocabulary from the product's own types: the kinds and their finality are read from
 * {@link JobEventKind}, so a build that changes which kinds end a job changes what is being asked
 * here. Each one is also shown to be capable of failing, against a deliberately broken mechanism,
 * because a property that cannot fail is a sentence rather than a check.</p>
 */
final class StateMachinePropertyTest {

    @Test
    @DisplayName("an operation reaches a terminal state once, from a state the actor read")
    void anoperationEndsOnce() {
        assertEquals(List.of(), Generated.counterexample(
                        Generated.sequences(OperationStateProperty.alphabet()),
                        OperationStateProperty::holds),
                "a generated sequence reached a terminal state twice with different outcomes, at"
                        + " seed " + Generated.SEED);
    }

    @Test
    @DisplayName("a broken transition table is found, so the property can fail")
    void abrokenTransitionTableIsFound() {
        final List<OperationStateProperty.Step> counterexample = Generated.counterexample(
                Generated.sequences(OperationStateProperty.alphabet()),
                sequence -> OperationStateProperty.holds(sequence,
                        OperationStateProperty.Fidelity.TERMINAL_IS_NOT_FINAL));
        assertTrue(!counterexample.isEmpty(),
                "a table that lets a terminal state be left held the property, which means the"
                        + " property is a sentence rather than a check");
        assertTrue(counterexample.size() <= OperationStateProperty.alphabet().size(),
                "the counterexample was not shrunk: " + counterexample);
    }

    @Test
    @DisplayName("no interleaving leaves both workers holding the lease, or writing after losing it")
    void noworkerWritesAfterLosingTheLease() {
        assertEquals(List.of(), Generated.counterexample(
                        Generated.sequences(LeaseProperty.alphabet()), LeaseProperty::holds),
                "a generated interleaving left a worker writing under a fence the lease had moved"
                        + " past, at seed " + Generated.SEED);
    }

    @Test
    @DisplayName("a lease compared by holder rather than by fence is found")
    void abrokenLeaseComparisonIsFound() {
        assertTrue(!Generated.counterexample(Generated.sequences(LeaseProperty.alphabet()),
                        sequence -> LeaseProperty.holds(sequence,
                                LeaseProperty.Fidelity.HOLDER_RATHER_THAN_FENCE)).isEmpty(),
                "comparing holders rather than fences held the property, and a worker that lost a"
                        + " lease mid-way believes it holds one until it next looks");
    }

    @Test
    @DisplayName("the snapshot is always the fold, and every counter is what the store holds")
    void thesnapshotIsTheFold() {
        assertEquals(List.of(), Generated.counterexample(
                        Generated.sequences(LedgerProperty.alphabet()), LedgerProperty::holds),
                "a generated sequence left the cheap answer and the true answer different, at seed "
                        + Generated.SEED);
    }

    @Test
    @DisplayName("a snapshot in a second commit and a counter without contents are both found")
    void bothbrokenMaterialisationsAreFound() {
        assertTrue(!Generated.counterexample(Generated.sequences(LedgerProperty.alphabet()),
                        sequence -> LedgerProperty.holds(sequence,
                                LedgerProperty.Fidelity.SNAPSHOT_IN_A_SECOND_COMMIT)).isEmpty(),
                "materialising the snapshot in a second commit held the property, and a process"
                        + " stopping between the two leaves a state nobody described");
        assertTrue(!Generated.counterexample(Generated.sequences(LedgerProperty.alphabet()),
                        sequence -> LedgerProperty.holds(sequence,
                                LedgerProperty.Fidelity.COUNTER_WITHOUT_CONTENTS)).isEmpty(),
                "a counter advanced without the contents held the property, which is capacity"
                        + " refused for work that is not there");
    }

    @Test
    @DisplayName("the recorded cases run without generation, so they cost nothing and never stop")
    void therecordedCasesRunWithoutGeneration() {
        RecordedCases.operationSequences().forEach(sequence ->
                assertTrue(OperationStateProperty.holds(sequence),
                        "a recorded case stopped holding: " + sequence));
        RecordedCases.leaseSequences().forEach(sequence ->
                assertTrue(LeaseProperty.holds(sequence),
                        "a recorded case stopped holding: " + sequence));
        RecordedCases.ledgerSequences().forEach(sequence ->
                assertTrue(LedgerProperty.holds(sequence),
                        "a recorded case stopped holding: " + sequence));
        assertTrue(!RecordedCases.operationSequences().isEmpty()
                        && !RecordedCases.leaseSequences().isEmpty()
                        && !RecordedCases.ledgerSequences().isEmpty(),
                "nothing is recorded, so a defect once found would be a memory rather than a test");
    }

    @Test
    @DisplayName("the generated interleavings include the two-worker races that matter")
    void thegeneratedShapesCoverTheRaces() {
        final long racing = Generated.sequences(LeaseProperty.alphabet()).stream()
                .filter(sequence -> sequence.stream()
                        .anyMatch(step -> step.worker() == 0 && step.act() == LeaseProperty.Act.TAKE)
                        && sequence.stream().anyMatch(step -> step.worker() == 1
                                && step.act() == LeaseProperty.Act.TAKE))
                .count();
        assertTrue(racing > Generated.SEQUENCES / 4,
                "only " + racing + " of " + Generated.SEQUENCES + " generated interleavings have"
                        + " both workers taking the lease, and the ones that do not are the ones"
                        + " that prove nothing about contention");
    }

    @Test
    @DisplayName("the vocabulary is the product's own, so a sixth kind changes what is asked here")
    void thevocabularyIsTheProductsOwn() {
        assertEquals(JobEventKind.values().length * JobEventKind.values().length,
                OperationStateProperty.alphabet().size(),
                "the alphabet no longer follows from the product's own kinds, so this property is"
                        + " about a machine that may not exist any more");
    }
}
