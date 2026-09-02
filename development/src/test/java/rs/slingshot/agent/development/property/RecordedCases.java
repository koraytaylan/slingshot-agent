// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.property;

import java.util.List;
import rs.slingshot.agent.wire.JobEventKind;

/**
 * The shrunk counterexamples, kept permanently, run without generation.
 *
 * <p>A defect a generator found once is a defect a generator might not find again — the seed
 * changes, the alphabet grows, the sequence that mattered stops being drawn. So every shrunk
 * counterexample is written down here and asked on every build, which costs nothing and never stops
 * running.</p>
 *
 * <p>Each one is minimal: the shrinker removed every step that could be removed while it still
 * broke the property, so what is left is what the defect actually needed.</p>
 */
final class RecordedCases {

    private RecordedCases() {
    }

    /**
     * The operation sequences worth keeping.
     *
     * <p>The first is the one a terminal-is-not-final table breaks on: end, then end differently.
     * It is two steps because two is all it takes, which is the point of shrinking.</p>
     *
     * @return the sequences
     */
    static List<List<OperationStateProperty.Step>> operationSequences() {
        return List.of(
                List.of(new OperationStateProperty.Step(JobEventKind.SUCCEEDED,
                                JobEventKind.ACCEPTED),
                        new OperationStateProperty.Step(JobEventKind.FAILED,
                                JobEventKind.SUCCEEDED)),
                List.of(new OperationStateProperty.Step(JobEventKind.STARTED,
                                JobEventKind.ACCEPTED),
                        new OperationStateProperty.Step(JobEventKind.PROGRESS,
                                JobEventKind.ACCEPTED)));
    }

    /**
     * The lease interleavings worth keeping.
     *
     * <p>The first is the handover a holder-rather-than-fence comparison gets wrong: one takes,
     * time passes until it expires, the other takes, and the first writes believing it still
     * holds.</p>
     *
     * @return the sequences
     */
    static List<List<LeaseProperty.Step>> leaseSequences() {
        return List.of(
                List.of(new LeaseProperty.Step(0, LeaseProperty.Act.TAKE),
                        new LeaseProperty.Step(0, LeaseProperty.Act.WAIT),
                        new LeaseProperty.Step(0, LeaseProperty.Act.WAIT),
                        new LeaseProperty.Step(0, LeaseProperty.Act.WAIT),
                        new LeaseProperty.Step(1, LeaseProperty.Act.TAKE),
                        new LeaseProperty.Step(0, LeaseProperty.Act.WRITE)),
                List.of(new LeaseProperty.Step(0, LeaseProperty.Act.TAKE),
                        new LeaseProperty.Step(1, LeaseProperty.Act.TAKE),
                        new LeaseProperty.Step(1, LeaseProperty.Act.WRITE)));
    }

    /**
     * The ledger sequences worth keeping.
     *
     * <p>The first is the pair that has to land together: an end, then anything at all, which a
     * second-commit materialisation breaks on immediately.</p>
     *
     * @return the sequences
     */
    static List<List<LedgerProperty.Step>> ledgerSequences() {
        return List.of(
                List.of(new LedgerProperty.Step(LedgerProperty.Act.END, 0),
                        new LedgerProperty.Step(LedgerProperty.Act.APPEND, 0)),
                List.of(new LedgerProperty.Step(LedgerProperty.Act.ADMIT, 0),
                        new LedgerProperty.Step(LedgerProperty.Act.ADMIT, 1),
                        new LedgerProperty.Step(LedgerProperty.Act.RELEASE, 0)));
    }
}
