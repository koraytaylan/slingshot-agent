// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.DocumentValue;

/**
 * How an answer arrives: inline, or as an artifact to fetch.
 *
 * <p>Two cases and no third, and no state in which both are present. An answer larger than the
 * envelope allows is not truncated — a truncated answer is not a smaller answer, it is an
 * unparseable one — so it is published as an artifact and the document says where to fetch it and
 * what it will digest to.</p>
 */
public sealed interface ResultDelivery permits ResultDelivery.Inline, ResultDelivery.Artifact {

    /** The member that says which of the two this is. */
    String DELIVERY = "delivery";

    /** The member inline bytes are carried in. */
    String INLINE = "inline_result";

    /** The member an artifact's byte count is carried in. */
    String BYTE_COUNT = "artifact_byte_count";

    /** The member an artifact's digest is carried in. */
    String ARTIFACT_DIGEST = "artifact_digest";

    /** Every member a delivery may carry, across both cases. */
    List<String> MEMBERS = List.of(ARTIFACT_DIGEST, BYTE_COUNT, DELIVERY, INLINE);

    /**
     * How this delivery is spelled on the wire.
     *
     * @return the spelling
     */
    String spelling();

    /**
     * An answer that fits inside the envelope.
     *
     * @param bytes the answer's own canonical bytes
     */
    record Inline(byte[] bytes) implements ResultDelivery {

        /** Holds bytes nothing else can change afterwards. */
        public Inline {
            bytes = bytes.clone();
        }

        /**
         * The answer's own canonical bytes.
         *
         * @return the bytes, as a copy nothing else holds
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String spelling() {
            return "inline";
        }

        /**
         * The answer, read as the text it is.
         *
         * @return the rendering
         */
        public String rendered() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * An answer published where it can be fetched.
     *
     * @param byteCount how many bytes it comes to
     * @param digest what those bytes digest to, so a fetch can be checked
     */
    record Artifact(long byteCount, DigestValue digest) implements ResultDelivery {

        @Override
        public String spelling() {
            return "artifact";
        }
    }

    /** Why a document is not a delivery. */
    enum Refusal {
        /** It carries both inline bytes and an artifact reference, and it is one or the other. */
        BOTH,
        /** It carries neither, and an answer arrives somehow. */
        NEITHER,
        /** It names a delivery this build does not know. */
        UNKNOWN_DELIVERY,
        /** A member is there and is not the kind of value it has to be. */
        WRONG_KIND_OF_VALUE,
        /** The inline bytes are past the bound the contract declares for them. */
        PAST_THE_INLINE_BOUND,
        /** The artifact digest is not sixty-four lower-case hexadecimal characters. */
        NOT_A_DIGEST
    }

    /** The result of reading one: the delivery, or the one reason there is none. */
    sealed interface Outcome permits Held, Refused {
    }

    /**
     * A document that says how an answer arrives.
     *
     * @param delivery the delivery it says
     */
    record Held(ResultDelivery delivery) implements Outcome {
    }

    /**
     * A document that does not.
     *
     * @param refusal why it does not
     * @param detail what was observed
     */
    record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * The delivery an answer of a given size takes.
     *
     * <p>Crossing the inline bound is a switch rather than a refusal: the client expects the larger
     * answer to exist somewhere, and refusing it would be this side deciding that an answer it
     * already produced does not count.</p>
     *
     * @param answer the answer's own canonical bytes
     * @param inlineBound how large an inline answer may be
     * @return the delivery
     */
    static ResultDelivery of(byte[] answer, long inlineBound) {
        if (answer.length <= inlineBound) {
            return new Inline(answer);
        }
        return new Artifact(answer.length, Digest.of(answer));
    }

    /**
     * Reads a delivery out of a document.
     *
     * @param mapping the document
     * @param inlineBound how large an inline answer may be
     * @return the delivery, or the one reason there is none
     */
    static Outcome read(DocumentValue.Mapping mapping, long inlineBound) {
        final Optional<String> named = text(mapping, DELIVERY);
        final boolean inline = mapping.member(INLINE).isPresent();
        final boolean artifact = mapping.member(BYTE_COUNT).isPresent()
                || mapping.member(ARTIFACT_DIGEST).isPresent();
        if (inline && artifact) {
            return new Refused(Refusal.BOTH,
                    "this document carries an answer and a place to fetch one");
        }
        if (!inline && !artifact) {
            return new Refused(Refusal.NEITHER, "this document carries no answer at all");
        }
        if (named.isEmpty()) {
            return new Refused(Refusal.WRONG_KIND_OF_VALUE, DELIVERY + " is not text");
        }
        return inline ? inline(mapping, named.get(), inlineBound) : artifact(mapping, named.get());
    }

    private static Outcome inline(DocumentValue.Mapping mapping, String named, long inlineBound) {
        if (!"inline".equals(named)) {
            return new Refused(Refusal.UNKNOWN_DELIVERY,
                    named + " does not name the delivery this document carries");
        }
        final Optional<String> answer = text(mapping, INLINE);
        if (answer.isEmpty()) {
            return new Refused(Refusal.WRONG_KIND_OF_VALUE, INLINE + " is not text");
        }
        final byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > inlineBound) {
            return new Refused(Refusal.PAST_THE_INLINE_BOUND, bytes.length
                    + " bytes is past the inline bound of " + inlineBound);
        }
        return new Held(new Inline(bytes));
    }

    private static Outcome artifact(DocumentValue.Mapping mapping, String named) {
        if (!"artifact".equals(named)) {
            return new Refused(Refusal.UNKNOWN_DELIVERY,
                    named + " does not name the delivery this document carries");
        }
        final Optional<Long> count = mapping.member(BYTE_COUNT)
                .filter(DocumentValue.Whole.class::isInstance)
                .map(value -> ((DocumentValue.Whole) value).value());
        final Optional<String> rendered = text(mapping, ARTIFACT_DIGEST);
        if (count.isEmpty() || rendered.isEmpty()) {
            return new Refused(Refusal.WRONG_KIND_OF_VALUE,
                    "an artifact carries a byte count and a digest");
        }
        final DigestValue.Outcome held = DigestValue.of(rendered.get());
        if (held instanceof final DigestValue.Refused refused) {
            return new Refused(Refusal.NOT_A_DIGEST, refused.refusal().toString());
        }
        return new Held(new Artifact(count.get(), ((DigestValue.Held) held).digest()));
    }

    private static Optional<String> text(DocumentValue.Mapping mapping, String member) {
        return mapping.member(member)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value());
    }
}
