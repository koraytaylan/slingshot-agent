// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

/**
 * Why a value cannot be written in the canonical form, and where in the value that is.
 *
 * <p>Refusing is the only honest answer. A writer that coerced what it could not represent would
 * produce bytes somebody digests, and the digest of a coerced value is a different identity from
 * the one the sender computed — discovered as a refused submission long after the coercion.</p>
 *
 * @param failure what cannot be written
 * @param pointer where in the value it is, as a pointer of member names and array positions
 * @param detail what was observed, so the cause is readable rather than inferred
 */
public record CanonicalRefusal(Failure failure, String pointer, String detail) {

    /** What the canonical form cannot carry. */
    public enum Failure {
        /** A string carries half of a character, so its bytes are not a string at all. */
        NOT_A_WELL_FORMED_STRING,
        /** A member name carries half of a character, and a name is a string. */
        NOT_A_WELL_FORMED_NAME
    }

    /**
     * Renders the refusal the way a failure message states one.
     *
     * @return the rendering, naming the failure, the pointer, and what was observed
     */
    public String rendered() {
        return failure + " at " + pointer + ": " + detail;
    }
}
