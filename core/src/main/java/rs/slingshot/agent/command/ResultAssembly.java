// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import rs.slingshot.agent.digest.Digest;
import rs.slingshot.agent.digest.DigestValue;

/**
 * A result measured as it is built, so an overflow is known before the whole answer is held.
 *
 * <p>Nothing is ever truncated. A truncated answer is not a smaller answer, it is an unparseable
 * one: a caller receiving half a document cannot tell it from a whole document about less, and the
 * client is built to fetch a large answer rather than to cope with a damaged one. So a result that
 * does not fit inline becomes an artifact the caller fetches, and the answer carries the count and
 * digest needed to check what comes back.</p>
 *
 * <h2>Why the measuring happens during assembly</h2>
 *
 * <p>The obvious implementation builds the whole result, measures it, and publishes it where it
 * turns out to be too large. That works until a command produces an answer far larger than the
 * bound — and the answers that overflow are exactly the ones large enough to matter. Holding one
 * whole in order to discover that it should not have been held is how a read command takes an
 * author instance down, which is the failure this plan exists to make impossible.</p>
 *
 * <p>So bytes are counted and digested as they arrive. While the total is within the bound they are
 * kept, because a small answer is served inline and keeping it is the cheapest thing to do. The
 * write that crosses the bound hands everything gathered so far to the overflow and releases it,
 * and every byte after that goes straight through. The most this ever holds is the bound plus one
 * write, whatever the size of the answer — and {@link #held()} reports that, so a suite can assert
 * it structurally rather than trust a comment claiming it.</p>
 */
public final class ResultAssembly implements AutoCloseable {

    private final long inlineBound;
    private final OutputStream overflow;

    /**
     * Every byte of the result, digested on its way to wherever it is going.
     *
     * <p>The digest is driven by the write rather than updated beside it. Updating it by hand
     * leaves three things that have to agree — the digest, the count, and what was actually
     * written — and the digest is the one nothing would notice had fallen behind until a caller
     * checked a fetched result against it and found it did not match.</p>
     */
    private final DigestOutputStream digested;

    /**
     * What has been gathered, in a holder rather than a field that is reassigned.
     *
     * <p>The buffer is replaced when the result spills rather than emptied, because emptying one
     * keeps its array and the array is the thing this is trying to stop holding. Every field here
     * is final and every piece of state sits behind one, which is this repository's way of saying
     * where the mutability is rather than scattering it.</p>
     */
    private final AtomicReference<ByteArrayOutputStream> gathered =
            new AtomicReference<>(new ByteArrayOutputStream());

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong held = new AtomicLong();
    private final AtomicReference<Where> where = new AtomicReference<>(Where.INLINE);

    private ResultAssembly(long inlineBound, OutputStream overflow) {
        this.inlineBound = inlineBound;
        this.overflow = overflow;
        this.digested = new DigestOutputStream(new Dispatching(), digestOf());
    }

    /**
     * Where a digested byte actually lands, which changes once during an assembly.
     *
     * <p>Not an inner lambda or a switch at each write: the destination is a property of the
     * assembly's state, and putting it behind the stream means the digest cannot be updated for a
     * byte that was never written, or a byte written that was never digested.</p>
     */
    private final class Dispatching extends OutputStream {

        @Override
        public void write(int one) {
            write(new byte[] {(byte) one}, 0, 1);
        }

        @Override
        public void write(byte[] chunk, int from, int length) {
            if (where.get() == Where.OVERFLOWED) {
                handOn(chunk, from, length);
                return;
            }
            if (count.get() > inlineBound) {
                spill(chunk, from, length);
                return;
            }
            final ByteArrayOutputStream keeping = gathered.get();
            keeping.write(chunk, from, length);
            held.accumulateAndGet(keeping.size(), Math::max);
        }
    }

    /** Where the bytes written so far went. */
    private enum Where {
        /** Gathered here, because they still fit in an answer. */
        INLINE,
        /** Handed to the overflow, because they stopped fitting. */
        OVERFLOWED
    }

    /**
     * An assembly bounded by what one command may answer inline.
     *
     * @param inlineBound the largest answer this command may carry in the answer itself
     * @param overflow where the bytes go once they stop fitting, written to only where they do
     * @return the assembly
     */
    public static ResultAssembly upTo(long inlineBound, OutputStream overflow) {
        return new ResultAssembly(inlineBound, overflow);
    }

    /**
     * Adds bytes to the result.
     *
     * @param chunk the bytes to take from
     * @param length how many of them to take
     */
    public void wrote(byte[] chunk, int length) {
        count.addAndGet(length);
        try {
            digested.write(chunk, 0, length);
        } catch (final IOException failure) {
            throw new UncheckedIOException("the result could not be written", failure);
        }
    }

    private void spill(byte[] chunk, int from, int length) {
        // What was gathered goes first so the overflow receives the result in order, and the
        // buffer is replaced rather than emptied: emptying one keeps its array, and the array is
        // the thing this is trying to stop holding.
        final byte[] sofar = gathered.get().toByteArray();
        held.accumulateAndGet(sofar.length, Math::max);
        handOn(sofar, 0, sofar.length);
        gathered.set(new ByteArrayOutputStream());
        handOn(chunk, from, length);
        where.set(Where.OVERFLOWED);
    }

    private void handOn(byte[] chunk, int from, int length) {
        try {
            overflow.write(chunk, from, length);
        } catch (final IOException failure) {
            throw new UncheckedIOException("the overflow stopped accepting bytes", failure);
        }
    }

    /**
     * The most this assembly ever held at once.
     *
     * <p>Reported so that "it never holds the whole result" is something a suite checks rather than
     * something a comment asserts.</p>
     *
     * @return the count, which never exceeds the bound plus one write
     */
    public long held() {
        return held.get();
    }

    /**
     * How large the whole result is.
     *
     * @return the count, whether the bytes were kept or handed on
     */
    public long count() {
        return count.get();
    }

    /** What a finished result turned out to be. */
    public sealed interface Assembled permits Inline, Overflowed {
    }

    /**
     * One that fits, and is carried in the answer itself.
     *
     * @param bytes the whole result
     */
    public record Inline(byte[] bytes) implements Assembled {

        /** Holds the bytes apart from whatever produced them. */
        public Inline {
            bytes = bytes.clone();
        }

        /**
         * The whole result.
         *
         * @return the bytes, which nothing else holds
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * One that does not, and went to the overflow instead.
     *
     * @param byteCount how many bytes there are, which a fetcher checks what arrived against
     * @param digest the digest of those bytes, which a fetcher checks for itself
     */
    public record Overflowed(long byteCount, DigestValue digest) implements Assembled {
    }

    /**
     * Finishes the result.
     *
     * @return the whole result where it fits, and the count and digest of it where it does not
     */
    public Assembled build() {
        return where.get() == Where.INLINE ? new Inline(gathered.get().toByteArray())
                : new Overflowed(count.get(),
                        DigestValue.ofBytes(digested.getMessageDigest().digest()));
    }

    @Override
    public void close() {
        gathered.set(new ByteArrayOutputStream());
    }

    private static MessageDigest digestOf() {
        try {
            return MessageDigest.getInstance(Digest.ALGORITHM);
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException("this runtime provides no " + Digest.ALGORITHM, absent);
        }
    }
}
