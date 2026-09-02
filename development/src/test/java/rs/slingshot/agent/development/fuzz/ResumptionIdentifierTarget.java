// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import java.nio.charset.StandardCharsets;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.identity.AgentOperationIdentifier;
import rs.slingshot.agent.store.ReplayCursor;

/**
 * Arbitrary text offered as a place to resume a stream from.
 *
 * <p>The other value a caller supplies that decides where this side reads. The property is about
 * reach rather than shape: nothing an identifier can be spelled as reaches an event belonging to
 * another operation, another subscription, or another generation — so a cursor that reads is
 * checked against the generation this instance is serving and the operation the caller asked
 * about, and one that names a different one is a refusal rather than a read.</p>
 */
public final class ResumptionIdentifierTarget implements FuzzTarget {

    /** How the fuzzing tool reaches this target. */
    private static final ResumptionIdentifierTarget TARGET = new ResumptionIdentifierTarget();

    private final AgentContract contract;

    /** Holds one target bound by the contract this build authenticated. */
    public ResumptionIdentifierTarget() {
        this.contract = ((AgentContract.Loaded) AgentContract.load()).contract();
    }

    /** The generation this instance is serving, which a cursor from another must not reach. */
    private static final long SERVING = 1;

    /** The operation the caller asked about. */
    private static final String THE_OPERATION =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

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
        final String rendered = new String(input, StandardCharsets.UTF_8);
        final Attempted.Answered<ReplayCursor.Outcome> asked =
                Attempted.of(() -> ReplayCursor.read(rendered));
        if (asked.threw()) {
            return FuzzOutcome.broken("reading a cursor answers rather than throws",
                    "it threw " + asked.threwWhat());
        }
        final ReplayCursor.Outcome cursor = asked.value().orElseThrow();
        if (cursor instanceof final ReplayCursor.Held held
                && held.cursor().generation().number() != SERVING) {
            return FuzzOutcome.broken("no cursor reaches another generation's events",
                    rendered + " read as generation " + held.cursor().generation().number()
                            + " while this instance serves " + SERVING);
        }
        return identifier(rendered, contract);
    }

    /**
     * That nothing spelled into an operation identifier reaches another operation's events.
     *
     * @param rendered what arrived
     * @param contract the authenticated contract, which bounds how long one may be
     * @return whether the property held
     */
    private static FuzzOutcome identifier(String rendered, AgentContract contract) {
        final Attempted.Answered<AgentOperationIdentifier.Outcome> asked =
                Attempted.of(() -> AgentOperationIdentifier.of(rendered, contract));
        if (asked.threw()) {
            return FuzzOutcome.broken("reading an identifier answers rather than throws",
                    "it threw " + asked.threwWhat());
        }
        final AgentOperationIdentifier.Outcome outcome = asked.value().orElseThrow();
        if (!(outcome instanceof final AgentOperationIdentifier.Held held)) {
            return FuzzOutcome.held();
        }
        return THE_OPERATION.equals(held.identifier().rendered())
                || !rendered.contains(THE_OPERATION)
                ? FuzzOutcome.held()
                : FuzzOutcome.broken("no identifier reaches another operation's events",
                        rendered + " read as " + held.identifier().rendered()
                                + " while carrying " + THE_OPERATION);
    }
}
