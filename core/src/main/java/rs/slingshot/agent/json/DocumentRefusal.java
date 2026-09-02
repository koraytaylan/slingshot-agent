// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

/**
 * Why a document was not read, and where reading stopped.
 *
 * <p>A refusal carries the position because a bound crossed at byte four is a different report from
 * the same bound crossed at byte four million, and whoever reads the failure is trying to work out
 * which. It carries nothing of the document: a refusal that handed back what had been read so far
 * would be the partial value this reader exists not to produce.</p>
 *
 * @param failure what was wrong
 * @param position the byte the reader had consumed up to when it stopped, counted from one
 * @param detail what was observed, so the cause is readable rather than inferred
 */
public record DocumentRefusal(Failure failure, long position, String detail) {

    /** What was wrong with a document. */
    public enum Failure {
        /** The document is longer than the bound the contract declares. */
        DOCUMENT_BYTES,
        /** A value nests deeper than the bound the contract declares. */
        NESTING_DEPTH,
        /** One object carries more members than the bound the contract declares. */
        OBJECT_MEMBERS,
        /** One member name or string value is longer than the bound the contract declares. */
        STRING_BYTES,
        /** One object names the same member twice, so two readers would disagree about it. */
        DUPLICATE_MEMBER,
        /** The document is complete and something follows it. */
        TRAILING_BYTES,
        /** The input ends part-way through a value. */
        UNTERMINATED,
        /** The input's declared length and what it actually carries are two different numbers. */
        LENGTH_MISMATCH,
        /** A byte that cannot be where it is. */
        MALFORMED,
        /** A number the canonical form cannot carry, which is any number that is not whole. */
        NOT_WHOLE
    }

    /**
     * Renders the refusal the way a failure message states one.
     *
     * @return the rendering, naming the failure, the position, and what was observed
     */
    public String rendered() {
        return failure + " at byte " + position + ": " + detail;
    }
}
