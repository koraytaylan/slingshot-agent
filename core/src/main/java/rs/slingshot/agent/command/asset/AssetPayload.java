// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.Base64;
import java.util.List;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The bytes one asset is made of, and what kind of file they are.
 *
 * <p>The media type is declared by the caller and checked against a closed set rather than sniffed
 * from the content. Sniffing is a guess that is right most of the time, and an asset stored under
 * the wrong type is one that renders wrongly for everybody afterwards — so the caller says what it
 * is, and a type this build does not accept is refused before a byte is written.</p>
 *
 * <p>Both sizes are bounded and both are checked: the encoded text as it arrived, and the bytes it
 * decodes to. Checking only the second would mean decoding whatever arrived in order to find out it
 * was too large, which is the work the first bound exists to avoid.</p>
 *
 * @param mediaType what kind of file it is
 * @param content the bytes
 */
public record AssetPayload(String mediaType, byte[] content) {

    /** The member a caller carries this in. */
    public static final String ARGUMENT_MEMBER = "payload";

    /** The member the encoded bytes are carried in. */
    public static final String ENCODED_CONTENT = "encoded_content";

    /** The member the media type is carried in. */
    public static final String MEDIA_TYPE = "media_type";

    /** Every member a payload's own document has, and there is no third. */
    public static final List<String> MEMBERS = List.of(ENCODED_CONTENT, MEDIA_TYPE);

    /**
     * The media types this build stores.
     *
     * <p>Closed on purpose. An agent that stored anything a caller named would be a way to put
     * arbitrary content into a repository under a name nobody checked, and the set of things a
     * digital asset library is for is not open-ended.</p>
     */
    public static final List<String> SUPPORTED_MEDIA_TYPES = List.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml",
            "application/pdf", "text/plain", "text/csv", "application/json",
            "video/mp4", "audio/mpeg");

    /** Holds the bytes apart from whatever the caller still has a reference to. */
    public AssetPayload {
        content = content.clone();
    }

    /**
     * The bytes this payload carries.
     *
     * @return a copy, so what is stored cannot be changed underneath the command that stored it
     */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /**
     * How large the asset will be.
     *
     * @return the count of bytes
     */
    public long byteLength() {
        return content.length;
    }

    /** Why a payload is not one this build stores. */
    public enum Refusal {
        /** The payload is not an object. */
        NOT_A_DOCUMENT,
        /** A member a payload needs is absent, or one nobody declared is present. */
        MEMBERS_WRONG,
        /** The encoded text is longer than the contract allows. */
        ENCODED_TOO_LARGE,
        /** The encoded text is not what this contract encodes bytes as. */
        NOT_ENCODED,
        /** The bytes it decodes to are more than the contract allows. */
        DECODED_TOO_LARGE,
        /** The media type is not one this build stores. */
        MEDIA_TYPE_UNSUPPORTED
    }

    /** The result of reading one: the payload, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A payload this build stores.
     *
     * @param payload the payload
     */
    public record Held(AssetPayload payload) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which never quotes the content
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one written payload.
     *
     * @param written the payload as the caller wrote it
     * @param contract the authenticated contract, which bounds both sizes
     * @return the payload, or the one reason there is none
     */
    public static Outcome of(DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "a payload is an object carrying encoded bytes and what kind of file they are");
        }
        if (!(mapping.member(ENCODED_CONTENT).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text encoded)
                || !(mapping.member(MEDIA_TYPE).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text type)
                || mapping.members().size() != MEMBERS.size()) {
            return new Refused(Refusal.MEMBERS_WRONG,
                    "a payload carries exactly " + ENCODED_CONTENT + " and " + MEDIA_TYPE);
        }
        if (!SUPPORTED_MEDIA_TYPES.contains(type.value())) {
            return new Refused(Refusal.MEDIA_TYPE_UNSUPPORTED, type.value() + " is not a kind of"
                    + " file this build stores. What it stores is " + SUPPORTED_MEDIA_TYPES + ".");
        }
        return decoded(encoded.value(), type.value(), contract);
    }

    private static Outcome decoded(String encoded, String mediaType, AgentContract contract) {
        final long encodedBound =
                contract.value(ContractLimit.MAXIMUM_INLINE_BINARY_ENCODED_BYTES);
        if (encoded.length() > encodedBound) {
            return new Refused(Refusal.ENCODED_TOO_LARGE, encoded.length() + " encoded characters"
                    + " is more than the " + encodedBound + " one payload may arrive as. Checked"
                    + " before decoding, because decoding it to find out is the work this bound"
                    + " exists to avoid.");
        }
        final byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
        } catch (final IllegalArgumentException malformed) {
            return new Refused(Refusal.NOT_ENCODED,
                    "the content is not encoded the way this contract encodes bytes");
        }
        final long decodedBound =
                contract.value(ContractLimit.MAXIMUM_INLINE_BINARY_DECODED_BYTES);
        if (content.length > decodedBound) {
            return new Refused(Refusal.DECODED_TOO_LARGE, content.length + " bytes is more than"
                    + " the " + decodedBound + " one asset may be created from");
        }
        return new Held(new AssetPayload(mediaType, content));
    }
}
