// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.execution;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.store.ArtifactSlot;

/**
 * What an execution ended as: the state it ended in and the answer it produced.
 *
 * <p>The answer is one of three things and never a mixture. It is carried inline where it is small
 * enough that a client gets it with the state; it is a reference to an artifact somebody else has
 * already committed where it is not; and it is nothing at all where the command produces nothing.
 * The three are separate types rather than a record with two halves and a flag, because a reference
 * beside inline bytes is a question — which one is the answer? — nobody should have to ask.</p>
 *
 * @param state the state the execution ended in, which is one of the two terminal ones
 * @param result the answer it produced
 * @param finishedAtUnixMilliseconds when it ended
 */
public record ExecutionOutcome(OperationState state, Result result,
                               long finishedAtUnixMilliseconds) {

    /** Why an outcome is not one this build will write down. */
    public enum Refusal {
        /** Its state is not terminal, and an outcome is what an execution ended as. */
        NOT_TERMINAL,
        /** Its inline answer is larger than one may be carried inline. */
        RESULT_TOO_LARGE
    }

    /** The result of reading an outcome: one this build writes down, or why it does not. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An outcome this build will write down.
     *
     * @param outcome the outcome
     */
    public record Held(ExecutionOutcome outcome) implements Outcome {
    }

    /**
     * One it will not.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** The answer an execution produced, which is one of three things and never a mixture. */
    public sealed interface Result permits Inline, Published, Nothing {
    }

    /**
     * An answer small enough to be carried with the state.
     *
     * @param document the answer's canonical bytes, as the text they were written as
     */
    public record Inline(String document) implements Result {
    }

    /**
     * A reference to bytes the artifact store has already committed.
     *
     * @param slot which slot holds them
     * @param byteCount how many bytes the store recorded
     * @param digest what the store recorded them as digesting to
     */
    public record Published(ArtifactSlot slot, long byteCount, DigestValue digest)
            implements Result {
    }

    /** That the command produces no answer at all. */
    public enum Nothing implements Result {
        /** There is nothing to return, which is different from an answer that is empty. */
        NOTHING_TO_RETURN
    }

    /** How a result's kind is spelled where it is written down. */
    public enum Kind {
        /** The answer is carried with the state. */
        INLINE("inline"),
        /** The answer is a reference to a committed artifact. */
        PUBLISHED("published"),
        /** There is no answer. */
        NOTHING("nothing");

        private final String spelling;

        Kind(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this kind is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The kind one spelling names.
         *
         * @param spelling the spelling
         * @return the kind, or nothing where this build knows no such kind
         */
        public static Optional<Kind> named(String spelling) {
            return java.util.Arrays.stream(values())
                    .filter(kind -> kind.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * Reads one outcome, against the bound an inline answer is held to.
     *
     * @param state the state the execution ended in
     * @param result the answer it produced
     * @param finishedAtUnixMilliseconds when it ended
     * @param contract the authenticated contract, which declares the inline bound
     * @return the outcome, or the one reason there is none
     */
    public static Outcome of(OperationState state, Result result,
                             long finishedAtUnixMilliseconds, AgentContract contract) {
        if (state.finality() != rs.slingshot.agent.wire.JobEventKind.Finality.ENDS) {
            return new Refused(Refusal.NOT_TERMINAL, state.spelling()
                    + " is not a state an execution ends in");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_AGENT_INLINE_RESULT_BYTES);
        if (result instanceof final Inline inline
                && inline.document().getBytes(StandardCharsets.UTF_8).length > bound) {
            return new Refused(Refusal.RESULT_TOO_LARGE,
                    inline.document().getBytes(StandardCharsets.UTF_8).length
                            + " bytes is past the bound of " + bound
                            + ", so this answer is published rather than carried");
        }
        return new Held(new ExecutionOutcome(state, result, finishedAtUnixMilliseconds));
    }

    /**
     * Which of the three kinds this outcome's answer is.
     *
     * @return the kind
     */
    public Kind kind() {
        if (result instanceof Inline) {
            return Kind.INLINE;
        }
        return result instanceof Published ? Kind.PUBLISHED : Kind.NOTHING;
    }

    /**
     * How many bytes this answer occupies where it is counted.
     *
     * @return the bytes an inline answer holds, and none for the other two
     */
    public long inlineBytes() {
        return result instanceof final Inline inline
                ? inline.document().getBytes(StandardCharsets.UTF_8).length
                : 0;
    }

    /**
     * The one reason an outcome was refused, where it was.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where there is an outcome
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
