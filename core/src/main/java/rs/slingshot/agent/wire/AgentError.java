// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One refusal: a stable code and the sentence that code carries.
 *
 * <p>The sentence is the code's own and nothing else. There is no way to interpolate a value into a
 * message here — no format string, no parameter, no builder — so no path, name, or submitted value
 * can leave through a refusal. What a caller needs in order to act is in the sentence the code
 * already carries; what they would need in order to debug this agent is in the agent's own logs,
 * where an operator can read it and a caller cannot.</p>
 */
public final class AgentError {

    /** The member the code is carried in. */
    public static final String CODE = "code";

    /** The member the message is carried in. */
    public static final String MESSAGE = "message";

    /** Every member an error document has, and there is no third. */
    public static final List<String> MEMBERS = List.of(CODE, MESSAGE);

    private final ErrorCode code;
    private final String message;

    private AgentError(ErrorCode code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * The two bounds an error document is held to.
     *
     * @param codeBytes how long a code may be
     * @param messageBytes how long a message may be
     */
    public record Bounds(long codeBytes, long messageBytes) {

        /**
         * The bounds the contract declares, which is where both of them live.
         *
         * @param contract the authenticated contract
         * @return the bounds
         */
        public static Bounds from(AgentContract contract) {
            return new Bounds(contract.value(ContractLimit.MAXIMUM_AGENT_ERROR_CODE_BYTES),
                    contract.value(ContractLimit.MAXIMUM_AGENT_ERROR_MESSAGE_BYTES));
        }
    }

    /** Why a document is not an error this build knows. */
    public enum Refusal {
        /** The document is not an object with a code and a message. */
        NOT_A_DOCUMENT,
        /** A member is missing, and an error is both or neither. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** A member is there and is not text. */
        NOT_TEXT,
        /** A member is empty, and neither may be. */
        EMPTY,
        /** A member is longer than the bound the contract declares for it. */
        TOO_LONG,
        /** The code is not one this build knows, and an unknown code is not passed through. */
        UNKNOWN_CODE
    }

    /** The result of reading one: the error, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that is an error this build knows.
     *
     * @param error the error it carried
     */
    public record Held(AgentError error) implements Outcome {
    }

    /**
     * A document that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed, naming the member rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * The error this build reports for one code.
     *
     * @param code the code
     * @return the error, carrying that code's own sentence
     */
    public static AgentError of(ErrorCode code) {
        return new AgentError(code, code.sentence());
    }

    /**
     * Reads an error document written by something else.
     *
     * @param document the document
     * @param bounds the bounds to read it under
     * @return the error, or the one reason there is none
     */
    public static Outcome read(DocumentValue document, Bounds bounds) {
        if (!(document instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an error is an object with two members");
        }
        final Optional<Refused> shape = shapeOf(mapping);
        if (shape.isPresent()) {
            return shape.get();
        }
        final Optional<Refused> bounded = bounded(mapping, CODE, bounds.codeBytes())
                .or(() -> bounded(mapping, MESSAGE, bounds.messageBytes()));
        if (bounded.isPresent()) {
            return bounded.get();
        }
        final Optional<ErrorCode> code = ErrorCode.named(text(mapping, CODE).orElseThrow());
        if (code.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_CODE,
                    "this build knows no such code, and an unknown code is not passed through");
        }
        return new Held(new AgentError(code.get(), text(mapping, MESSAGE).orElseThrow()));
    }

    private static Optional<Refused> shapeOf(DocumentValue.Mapping mapping) {
        final Optional<Refused> unknown = mapping.members().keySet().stream()
                .filter(name -> !MEMBERS.contains(name))
                .map(name -> new Refused(Refusal.MEMBER_UNKNOWN, name + " is not a member an error"
                        + " document has"))
                .findFirst();
        if (unknown.isPresent()) {
            return unknown;
        }
        return MEMBERS.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .map(member -> new Refused(Refusal.MEMBER_ABSENT,
                        member + " is missing, and an error is both members or neither"))
                .findFirst();
    }

    private static Optional<Refused> bounded(DocumentValue.Mapping mapping, String member,
                                             long bound) {
        final Optional<String> value = text(mapping, member);
        if (value.isEmpty()) {
            return Optional.of(new Refused(Refusal.NOT_TEXT, member + " is not text"));
        }
        final int length = value.get().getBytes(StandardCharsets.UTF_8).length;
        if (length == 0) {
            return Optional.of(new Refused(Refusal.EMPTY, member + " is empty"));
        }
        if (length > bound) {
            return Optional.of(new Refused(Refusal.TOO_LONG,
                    member + " is " + length + " bytes, past the bound of " + bound));
        }
        return Optional.empty();
    }

    private static Optional<String> text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
    }

    /**
     * The code this error carries.
     *
     * @return the code
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * The message this error carries.
     *
     * @return the message
     */
    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final AgentError error && code == error.code
                && message.equals(error.message);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(code, message);
    }

    @Override
    public String toString() {
        return code.spelling();
    }
}
