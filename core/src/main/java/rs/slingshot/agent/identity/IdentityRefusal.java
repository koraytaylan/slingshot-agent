// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

/**
 * Why a document is not an identity, naming the member it is not one because of.
 *
 * <p>The member is named because all five fields are required and a caller told only that
 * "the identity was refused" has five places to look. Nothing here carries a value the document
 * held: a refusal is about shape, and a refusal that echoed a submitted value would be a way to
 * have somebody else's bytes read back.</p>
 *
 * @param failure what was wrong
 * @param member the member it was wrong about
 * @param detail what was observed, so the cause is readable rather than inferred
 */
public record IdentityRefusal(Failure failure, String member, String detail) {

    /** What can be wrong with an identity document. */
    public enum Failure {
        /** One of the five is missing, and there is no identity without all five. */
        MEMBER_ABSENT,
        /** A member nobody declared is present, and ignoring it would honour nothing. */
        MEMBER_UNKNOWN,
        /** A member is there and is not text, which no member of this document ever is. */
        NOT_TEXT,
        /** A member is empty, and no member of this document may be. */
        EMPTY,
        /** A member is longer than the bound the contract declares for it. */
        TOO_LONG,
        /** A digest member is not sixty-four lower-case hexadecimal characters. */
        NOT_A_DIGEST,
        /** A number is outside the range its member permits. */
        OUT_OF_RANGE,
        /** The format is anything but the one exact constant this build means. */
        FORMAT_NOT_EXACT,
        /** The transport contract the document means is not the one this build means. */
        TRANSPORT_CONTRACT_MISMATCH,
        /** The canonical-form contract the document means is not the one this build means. */
        CANONICAL_CONTRACT_MISMATCH,
        /** The document is not an object at all. */
        NOT_A_DOCUMENT
    }

    /**
     * Renders the refusal the way a failure message states one.
     *
     * @return the rendering, naming the failure, the member, and what was observed
     */
    public String rendered() {
        return failure + " at " + member + ": " + detail;
    }
}
