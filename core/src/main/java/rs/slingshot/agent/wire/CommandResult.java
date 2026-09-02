// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The answer one command produced, delivered inline or as an artifact.
 *
 * <p>A result and a failure are two documents rather than two halves of one, and a document that is
 * both is refused: a command either produced an answer or did not, and a caller reading a document
 * that says both has to decide which half to believe.</p>
 *
 * @param delivery how the answer arrives
 */
public record CommandResult(ResultDelivery delivery) {

    /** Every member a result document has, which are the delivery's own. */
    public static final List<String> MEMBERS = ResultDelivery.MEMBERS;

    /** Why a document is not a result. */
    public enum Refusal {
        /** The document is not an object. */
        NOT_A_DOCUMENT,
        /** It also carries a failure category, and a command produced an answer or did not. */
        CARRIES_A_FAILURE,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The delivery it says is not one, for a reason the delivery names. */
        DELIVERY_REFUSED
    }

    /** The result of reading one: the result, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that is an answer.
     *
     * @param result the result it carried
     */
    public record Held(CommandResult result) implements Outcome {
    }

    /**
     * A document that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * The result an answer of a given size produces.
     *
     * @param answer the answer's own canonical bytes
     * @param contract the authenticated contract, which declares the inline bound
     * @return the result, inline where it fits and as an artifact where it does not
     */
    public static CommandResult of(byte[] answer, AgentContract contract) {
        return new CommandResult(ResultDelivery.of(answer,
                contract.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES)));
    }

    /**
     * Reads a result document.
     *
     * @param document the document
     * @param contract the authenticated contract, which declares the inline bound
     * @return the result, or the one reason there is none
     */
    public static Outcome read(DocumentValue document, AgentContract contract) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "a result is an object");
        }
        final Optional<String> failure = mapping.members().keySet().stream()
                .filter(CommandFailure.MEMBERS::contains)
                .findFirst();
        if (failure.isPresent()) {
            return new Refused(Refusal.CARRIES_A_FAILURE, failure.get()
                    + " belongs to a failure, and a command produced an answer or did not");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member a result document has");
        }
        final ResultDelivery.Outcome delivery = ResultDelivery.read(mapping,
                contract.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES));
        if (delivery instanceof final ResultDelivery.Refused refused) {
            return new Refused(Refusal.DELIVERY_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        return new Held(new CommandResult(((ResultDelivery.Held) delivery).delivery()));
    }
}
