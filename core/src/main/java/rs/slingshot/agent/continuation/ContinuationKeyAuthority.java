// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.continuation;

/**
 * The one authority every deployment provides, whatever it is running on.
 *
 * <p>A continuation token is only meaningful if the key that signed it is still the key the far
 * side has, and that is a question about durable shared state rather than about one process. So the
 * contract is a small linearizable store: read a ring, write it only if it still holds what the
 * caller expected, and only while the caller holds the lease.</p>
 *
 * <p>Every deployment implements all of it. A single instance is not permitted a cheaper version,
 * because the guarantees would then change the day somebody added a node — and the code depending
 * on them would not know. Nothing here observes node count, and nothing branches on which
 * deployment it is.</p>
 */
public interface ContinuationKeyAuthority {

    /** The result of reading: the ring, or the one reason there is none. */
    sealed interface ReadOutcome permits Read, Unavailable {
    }

    /**
     * A ring this deployment holds.
     *
     * @param ring the ring
     */
    record Read(KeyRing ring) implements ReadOutcome {
    }

    /**
     * No ring, for a reason that is never "so one was created".
     *
     * @param refusal why there is none
     */
    record Unavailable(KeyRingRefusal refusal) implements ReadOutcome {
    }

    /** The result of writing: it was written, or the one reason it was not. */
    sealed interface WriteOutcome permits Written, NotWritten {
    }

    /**
     * A ring that is now what the caller asked for.
     *
     * @param ring what the authority now holds
     */
    record Written(KeyRing ring) implements WriteOutcome {
    }

    /**
     * A write that did not happen, and left what was there untouched.
     *
     * @param refusal why it did not happen
     */
    record NotWritten(KeyRingRefusal refusal) implements WriteOutcome {
    }

    /**
     * What a caller holds while it may write, and nothing else does.
     *
     * @param holder who holds it
     * @param expiresAtUnixMilliseconds when it stops being held
     */
    record Lease(String holder, long expiresAtUnixMilliseconds) {
    }

    /**
     * The ring this deployment holds, or the reason it holds none.
     *
     * <p>A ring is never created by reading for one. A deployment that was never prepared is a
     * thing an operator has to know about, and a read that quietly created a ring would turn it
     * into a thing nobody ever finds out about until every token issued before it stops
     * validating.</p>
     *
     * @return the ring, or the one reason there is none
     */
    ReadOutcome read();

    /**
     * Writes a ring, only if what is held is still what the caller read, and only under a lease.
     *
     * @param expected what the caller read
     * @param next what it wants held instead
     * @param lease the lease the caller holds
     * @param nowUnixMilliseconds what this side's clock says
     * @return what is held now, or the one reason nothing was written
     */
    WriteOutcome compareAndSet(KeyRing expected, KeyRing next, Lease lease,
                               long nowUnixMilliseconds);
}
