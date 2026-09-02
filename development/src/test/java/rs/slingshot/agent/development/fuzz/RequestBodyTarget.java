// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.io.ByteArrayInputStream;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.http.BoundedRequestBody;
import rs.slingshot.agent.http.FramingPolicy;

/**
 * The bounded body reader as a servlet uses it, given bytes and a length that may be a lie.
 *
 * <p>This is where a hostile sender aims, and the two halves are separate on purpose. The framing
 * decision is made before a byte is read — a body that declares one length and is another, or
 * arrives under a coding this build did not ask for, is refused without reading it — and the reader
 * is then held to what framing said. The declared length is generated from the input rather than
 * taken from it, because a sender controls both and the interesting inputs are the ones where the
 * two disagree.</p>
 */
public final class RequestBodyTarget implements FuzzTarget {

    /** How the fuzzing tool reaches this target. */
    private static final RequestBodyTarget TARGET = new RequestBodyTarget();

    /** The lengths a sender might declare for one body, including the honest one. */
    private static final long[] DECLARED_OFFSETS = {0, 1, -1, 4096};

    private final AgentContract contract;

    /** Holds one target bound by the contract this build authenticated. */
    public RequestBodyTarget() {
        this.contract = ((AgentContract.Loaded) AgentContract.load()).contract();
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
        for (final long offset : DECLARED_OFFSETS) {
            final FuzzOutcome outcome = read(input, input.length + offset);
            if (outcome instanceof FuzzOutcome.Broken) {
                return outcome;
            }
        }
        return framing(input);
    }

    /**
     * One read at one declared length.
     *
     * @param input the bytes that arrive
     * @param declaredLength what the sender said would arrive
     * @return whether the property held
     */
    private FuzzOutcome read(byte[] input, long declaredLength) {
        final Attempted.Answered<BoundedRequestBody.Outcome> asked = Attempted.of(() ->
                BoundedRequestBody.read(new ByteArrayInputStream(input), declaredLength, contract));
        if (asked.threw()) {
            return FuzzOutcome.broken("the body reader answers rather than throws",
                    "it threw " + asked.threwWhat() + " for a declared length of "
                            + declaredLength);
        }
        final BoundedRequestBody.Outcome outcome = asked.value().orElseThrow();
        if (!(outcome instanceof final BoundedRequestBody.Read read)) {
            return FuzzOutcome.held();
        }
        if (read.bytes().length != input.length) {
            return FuzzOutcome.broken("what is read is what arrived",
                    read.bytes().length + " bytes came out of " + input.length);
        }
        return declaredLength >= 0 && declaredLength != input.length
                ? FuzzOutcome.broken("a body that is not the length it declared is refused",
                        input.length + " bytes were accepted under a declared " + declaredLength)
                : FuzzOutcome.held();
    }

    /**
     * The decision made before a byte is read, which is where an ambiguous frame is caught.
     *
     * @param input the bytes, whose first byte chooses the shape asked about
     * @return whether the property held
     */
    private static FuzzOutcome framing(byte[] input) {
        final long declared = input.length == 0 ? FramingPolicy.NO_LENGTH_DECLARED : input[0];
        final FramingPolicy.Chunked chunked = input.length > 1 && input[1] % 2 == 0
                ? FramingPolicy.Chunked.FRAMED_IN_CHUNKS : FramingPolicy.Chunked.NOT_FRAMED_IN_CHUNKS;
        final String coding = input.length > 2 ? "coding-" + (input[2] & 0xff) : "";
        final Attempted.Answered<FramingPolicy.Outcome> asked =
                Attempted.of(() -> new FramingPolicy(declared, chunked, coding).read());
        return asked.threw()
                ? FuzzOutcome.broken("framing answers rather than throws",
                        "it threw " + asked.threwWhat() + " for a declared " + declared
                                + " under " + chunked + " and " + coding)
                : FuzzOutcome.held();
    }
}
