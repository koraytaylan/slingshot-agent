// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.digest;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * One digest, which can only be a whole one.
 *
 * <p>The type refuses at construction everything that is not sixty-four lower-case hexadecimal
 * characters, so an invalid digest cannot exist to be compared. Rendering is lower-case because the
 * committed digest files are, and a comparison that had to case-fold first would be a comparison
 * with a step in it that somebody could forget.</p>
 *
 * <p>Comparison examines every byte. {@link #equals(Object)} is the same comparison rather than a
 * second one, so there is no early-returning equality anywhere on this type — including the one the
 * compiler would have written for a record, which is why this is not one.</p>
 */
public final class DigestValue {

    /** How many characters a rendered digest has, which is the only length this type holds. */
    public static final int RENDERED_LENGTH = 64;

    /** How many bytes those characters stand for. */
    public static final int BYTE_LENGTH = 32;

    private final byte[] value;

    private DigestValue(byte[] value) {
        this.value = value;
    }

    /** Why a rendering is not a digest. */
    public enum Refusal {
        /** It is shorter than a whole digest, so some of it is missing. */
        TOO_SHORT,
        /** It is longer than a whole digest, so some of it is something else. */
        TOO_LONG,
        /** It carries an upper-case character, which the committed digest files never do. */
        NOT_LOWER_CASE,
        /** It carries something that is not a hexadecimal character at all. */
        NOT_HEXADECIMAL
    }

    /** The result of holding a rendering: a digest, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A rendering that was a whole digest.
     *
     * @param digest the digest it holds
     */
    public record Held(DigestValue digest) implements Outcome {
    }

    /**
     * A rendering that was not one.
     *
     * @param refusal why it is not a digest
     * @param detail what was observed, so the cause is readable rather than inferred
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Holds a rendered digest, or says why it is not one.
     *
     * @param rendered the rendering, in lower-case hexadecimal
     * @return the digest, or the one reason there is none
     */
    public static Outcome of(String rendered) {
        if (rendered.length() < RENDERED_LENGTH) {
            return new Refused(Refusal.TOO_SHORT,
                    rendered.length() + " characters is short of " + RENDERED_LENGTH);
        }
        if (rendered.length() > RENDERED_LENGTH) {
            return new Refused(Refusal.TOO_LONG,
                    rendered.length() + " characters is past " + RENDERED_LENGTH);
        }
        return heldOrRefused(rendered);
    }

    private static Outcome heldOrRefused(String rendered) {
        final long upper = rendered.chars().filter(DigestValue::isUpperCaseHexadecimal).count();
        if (upper > 0) {
            return new Refused(Refusal.NOT_LOWER_CASE,
                    rendered + " carries " + upper + " upper-case characters");
        }
        final long outside = rendered.chars().filter(character -> !isHexadecimal(character)).count();
        if (outside > 0) {
            return new Refused(Refusal.NOT_HEXADECIMAL,
                    rendered + " carries " + outside + " characters that are not hexadecimal");
        }
        return new Held(new DigestValue(HexFormat.of().parseHex(rendered)));
    }

    private static boolean isHexadecimal(int character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f';
    }

    private static boolean isUpperCaseHexadecimal(int character) {
        return character >= 'A' && character <= 'F';
    }

    /**
     * Holds the digest these bytes already are.
     *
     * @param value the digest's own bytes
     * @return the digest
     * @throws IllegalArgumentException if the bytes are not a whole digest, because a digest of the
     *     wrong length is not a short digest but a defect in whatever produced it
     */
    public static DigestValue ofBytes(byte[] value) {
        if (value.length != BYTE_LENGTH) {
            throw new IllegalArgumentException(value.length + " bytes is not a digest");
        }
        return new DigestValue(value.clone());
    }

    /**
     * Whether this digest and another are the same digest.
     *
     * <p>Every byte is examined whatever the first difference is, because how far a comparison got
     * is itself an answer to whoever supplied one of the two.</p>
     *
     * @param other the other digest
     * @return whether they are the same
     */
    public boolean matches(DigestValue other) {
        return MessageDigest.isEqual(value, other.value);
    }

    /**
     * This digest's own bytes.
     *
     * @return the bytes, as a copy nothing else holds
     */
    public byte[] bytes() {
        return value.clone();
    }

    /**
     * This digest, rendered the way the committed digest files render one.
     *
     * @return the rendering, in lower-case hexadecimal
     */
    public String rendered() {
        return HexFormat.of().formatHex(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final DigestValue digest && matches(digest);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return rendered();
    }
}
