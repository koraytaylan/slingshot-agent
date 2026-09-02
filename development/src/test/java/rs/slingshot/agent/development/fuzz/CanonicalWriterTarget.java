// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.util.Arrays;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.CanonicalByteWriter;
import rs.slingshot.agent.json.DocumentRefusal;

/**
 * The other direction, which is the one the five-field identity rests on.
 *
 * <p>Two properties. A value the reader accepted writes to bytes the reader accepts back, and
 * writing the same value twice produces identical bytes. The second is the one with teeth: a value
 * whose canonical bytes are not stable is a submission whose derived digest differs from the
 * client's, refused in production for a reason nobody can see from either side.</p>
 */
public final class CanonicalWriterTarget implements FuzzTarget {

    /** How the fuzzing tool reaches this target. */
    private static final CanonicalWriterTarget TARGET = new CanonicalWriterTarget();

    private final BoundedDocumentReader.Bounds bounds;

    /** Holds one target bound by the contract this build authenticated. */
    public CanonicalWriterTarget() {
        this.bounds = BoundedDocumentReader.Bounds.from(
                ((AgentContract.Loaded) AgentContract.load()).contract());
    }

    /**
     * The entry point the fuzzing tool calls.
     *
     * @param input arbitrary bytes
     */
    public static void fuzzerTestOneInput(byte[] input) {
        final FuzzOutcome outcome = TARGET.of(input);
        if (outcome instanceof final FuzzOutcome.Broken broken) {
            throw new AssertionError(broken.property() + ": " + broken.detail());
        }
    }

    @Override
    public FuzzOutcome of(byte[] input) {
        if (!(readOf(input) instanceof final BoundedDocumentReader.Read read)) {
            return FuzzOutcome.held();
        }
        final Attempted.Answered<CanonicalByteWriter.Outcome> asked =
                Attempted.of(() -> CanonicalByteWriter.write(read.value()));
        if (asked.threw()) {
            return FuzzOutcome.broken("the writer answers rather than throws",
                    "it threw " + asked.threwWhat());
        }
        final CanonicalByteWriter.Outcome first = asked.value().orElseThrow();
        final CanonicalByteWriter.Outcome second = CanonicalByteWriter.write(read.value());
        if (!(first instanceof final CanonicalByteWriter.Written written)) {
            return FuzzOutcome.held();
        }
        if (!(second instanceof final CanonicalByteWriter.Written again)
                || !Arrays.equals(written.bytes(), again.bytes())) {
            return FuzzOutcome.broken("writing the same value twice produces the same bytes",
                    "the second write differed from the first");
        }
        return readOf(written.bytes()) instanceof BoundedDocumentReader.Read
                ? FuzzOutcome.held()
                : FuzzOutcome.broken("what the writer wrote the reader reads back",
                        "the canonical bytes of an accepted value were refused");
    }

    /**
     * One read, where a read that threw is treated as one that refused.
     *
     * <p>This target's property is about the writer. A reader that threw is the other target's
     * finding, and reporting it here as well would report one defect twice.</p>
     *
     * @param input the bytes
     * @return what the reader answered, or a refusal where it threw
     */
    private BoundedDocumentReader.Outcome readOf(byte[] input) {
        final Attempted.Answered<BoundedDocumentReader.Outcome> asked =
                Attempted.of(() -> BoundedDocumentReader.read(input, bounds));
        return asked.value().orElseGet(() -> new BoundedDocumentReader.Refused(
                new DocumentRefusal(DocumentRefusal.Failure.MALFORMED, 0,
                        "the reader threw, which is the other target's finding")));
    }
}
