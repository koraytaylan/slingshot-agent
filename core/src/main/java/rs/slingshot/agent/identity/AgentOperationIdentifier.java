// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;

/**
 * The name one logical operation has, for as long as the store that holds it does.
 *
 * <p>It is sixty-four lower-case hexadecimal characters, and it is also held to the identifier byte
 * bound the contract declares — whichever of the two is stricter. Two rules rather than one because
 * they say different things: the shape is what the client and this side agree an identifier looks
 * like, and the bound is what a store and a transport will carry. A build where the contract's
 * bound dropped below the shape would refuse identifiers rather than truncate them.</p>
 */
public final class AgentOperationIdentifier {

    private final String rendered;

    private AgentOperationIdentifier(String rendered) {
        this.rendered = rendered;
    }

    /** Why something is not an operation identifier. */
    public enum Refusal {
        /** It is not sixty-four lower-case hexadecimal characters. */
        NOT_THE_SHAPE,
        /** It is longer than the identifier bound the contract declares. */
        PAST_THE_BOUND
    }

    /** The result of holding one: the identifier, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * Something that is an operation identifier.
     *
     * @param identifier the identifier it is
     */
    public record Held(AgentOperationIdentifier identifier) implements Outcome {
    }

    /**
     * Something that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed, so the cause is readable rather than inferred
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Holds an operation identifier.
     *
     * @param rendered the identifier as it was written
     * @param contract the authenticated contract, which declares the bound
     * @return the identifier, or the one reason there is none
     */
    public static Outcome of(String rendered, AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_AGENT_OPERATION_IDENTIFIER_BYTES);
        if (rendered.length() > bound) {
            return new Refused(Refusal.PAST_THE_BOUND,
                    rendered.length() + " characters is past the bound of " + bound);
        }
        final DigestValue.Outcome shaped = DigestValue.of(rendered);
        if (shaped instanceof final DigestValue.Refused refused) {
            return new Refused(Refusal.NOT_THE_SHAPE,
                    refused.refusal() + ": " + refused.detail());
        }
        return new Held(new AgentOperationIdentifier(rendered));
    }

    /**
     * The identifier as it is written.
     *
     * @return the identifier
     */
    public String rendered() {
        return rendered;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final AgentOperationIdentifier identifier
                && rendered.equals(identifier.rendered);
    }

    @Override
    public int hashCode() {
        return rendered.hashCode();
    }

    @Override
    public String toString() {
        return rendered;
    }
}
