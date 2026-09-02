// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.util.Arrays;
import rs.slingshot.agent.continuation.ContinuationState;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.continuation.KeyRing;
import rs.slingshot.agent.continuation.QueryDigest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Arbitrary bytes offered as a continuation token, and the two properties that matter.
 *
 * <p>Nothing validates that was not issued under a key this authority holds — a forged token that
 * validated would be a caller reading somebody else's results, which is a different and much worse
 * failure than a malformed one being accepted. And every refusal is one of the six categories the
 * client's own registry declares, because a seventh outcome is one the client has no branch for.
 * </p>
 *
 * <p>The interesting inputs are near-valid rather than obviously wrong, so the target does the
 * mutating: it takes whatever bytes it is given as the token's state, signs nothing, and also
 * re-signs under a key the authority does not hold. Random noise gets refused at the first byte and
 * proves very little.</p>
 */
public final class ContinuationTokenTarget implements FuzzTarget {

    /** How the fuzzing tool reaches this target. */
    private static final ContinuationTokenTarget TARGET = new ContinuationTokenTarget();

    /** A key this authority does not hold, which is what a forgery would be signed under. */
    private static final String A_FOREIGN_KEY = "a-key-this-authority-does-not-hold";

    /** The key the ring holds, stood up here rather than read from a running instance. */
    private static final String THE_HELD_KEY = "the-key-this-authority-holds";

    /** When this target's clock says it is, which is inside every state it issues. */
    private static final long NOW = 1_000_000;

    /** How long a state this target issues stays valid, in the same units. */
    private static final long A_WHILE = 900_000;

    private final AgentContract contract;
    private final KeyRing ring;

    /** Holds one target over a stood-up ring and the contract this build authenticated. */
    public ContinuationTokenTarget() {
        this.contract = ((AgentContract.Loaded) AgentContract.load()).contract();
        this.ring = KeyRing.initial(THE_HELD_KEY);
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
        final ContinuationState state = stateFrom(input);
        final FuzzOutcome forged = refuses(ContinuationToken.issue(state, A_FOREIGN_KEY),
                "a token signed under a key this authority does not hold");
        if (forged instanceof FuzzOutcome.Broken) {
            return forged;
        }
        final FuzzOutcome mutated = refuses(
                ContinuationToken.arrived(mutatedIntegrity(input), state),
                "a token whose integrity value was mutated");
        if (mutated instanceof FuzzOutcome.Broken) {
            return mutated;
        }
        return honours(state);
    }

    /**
     * That one token is refused, and refused under a declared category.
     *
     * @param token the token to offer
     * @param what how the finding would describe it
     * @return whether the property held
     */
    private FuzzOutcome refuses(ContinuationToken token, String what) {
        final Attempted.Answered<ContinuationToken.Outcome> asked = Attempted.of(() ->
                token.validate(ring, targetDigest(), queryDigest(), generation(), NOW, contract));
        if (asked.threw()) {
            return FuzzOutcome.broken("validation answers rather than throws",
                    what + " threw " + asked.threwWhat());
        }
        final ContinuationToken.Outcome outcome = asked.value().orElseThrow();
        if (outcome instanceof ContinuationToken.Honoured) {
            return FuzzOutcome.broken("nothing validates that this authority did not issue",
                    what + " was honoured");
        }
        final ContinuationToken.Refusal refusal =
                ((ContinuationToken.Refused) outcome).refusal();
        return Arrays.asList(ContinuationToken.Refusal.values()).contains(refusal)
                ? FuzzOutcome.held()
                : FuzzOutcome.broken("every refusal is one of the six declared categories",
                        what + " was refused as " + refusal);
    }

    /**
     * That a token this authority did issue is honoured, so the property above is not vacuous.
     *
     * @param state the state to issue over
     * @return whether the property held
     */
    private FuzzOutcome honours(ContinuationState state) {
        return ContinuationToken.issue(state, THE_HELD_KEY)
                .validate(ring, targetDigest(), queryDigest(), generation(), NOW, contract)
                instanceof ContinuationToken.Honoured
                ? FuzzOutcome.held()
                : FuzzOutcome.broken("a token this authority issued is honoured",
                        "nothing this authority issued validated, so refusing everything would"
                                + " pass the property above");
    }

    private ContinuationState stateFrom(byte[] input) {
        return new ContinuationState(generation(), targetDigest(), queryDigest().value(),
                offsetFrom(input), NOW + A_WHILE);
    }

    /**
     * A resume offset taken from the input, so the state differs input by input.
     *
     * @param input the bytes
     * @return an offset inside what a state may carry
     */
    private static long offsetFrom(byte[] input) {
        long offset = 0;
        for (final byte held : input) {
            offset = (offset * 31 + (held & 0xff)) % 1_000_000;
        }
        return offset;
    }

    private static DigestValue mutatedIntegrity(byte[] input) {
        final StringBuilder rendered = new StringBuilder();
        for (int character = 0; character < 64; character++) {
            rendered.append("0123456789abcdef".charAt(
                    (character + (input.length == 0 ? 0 : input[character % input.length])) & 15));
        }
        return ((DigestValue.Held) DigestValue.of(rendered.toString())).digest();
    }

    private static DigestValue targetDigest() {
        return ((DigestValue.Held) DigestValue.of(
                "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8")).digest();
    }

    private static QueryDigest queryDigest() {
        return ((QueryDigest.Held) QueryDigest.of("query_paths",
                new DocumentValue.Mapping(new java.util.LinkedHashMap<>()))).digest();
    }

    private static EventStoreGeneration generation() {
        return ((EventStoreGeneration.Held) EventStoreGeneration.of(1)).generation();
    }
}
