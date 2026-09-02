// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.digest;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one digest this repository takes, over bytes it holds and over bytes it does not.
 *
 * <p>An artifact is digested as it is read rather than after it is collected, because the whole
 * point of a bounded store is that nothing holds an artifact-sized value in memory to ask a
 * question about it. The two ways of taking the digest answer the same value for the same content,
 * which is what lets one of them be used where the other cannot.</p>
 */
public final class Digest {

    /** The algorithm every digest in this repository is taken with. */
    public static final String ALGORITHM = "SHA-256";

    /**
     * How much of a stream is read at a time.
     *
     * <p>This is a buffer rather than a bound: it decides how often the algorithm is fed, and
     * nothing about how large the content may be. It is public so that a suite proving the streamed
     * and whole-input digests agree can use an input larger than one read rather than one larger
     * than a number it repeated here.</p>
     */
    public static final int READ_BUFFER_BYTES = 16384;

    private Digest() {
    }

    /**
     * The digest of bytes already in hand.
     *
     * @param bytes the content
     * @return its digest
     */
    public static DigestValue of(byte[] bytes) {
        return DigestValue.ofBytes(algorithm().digest(bytes));
    }

    /**
     * The digest of a stream, taken as it is read.
     *
     * <p>The stream is read to its end and not closed: whoever opened it decides when it is done
     * with, and a digest that closed somebody else's stream would be a digest with a side effect.
     * </p>
     *
     * @param stream the content
     * @return its digest
     * @throws IOException if the stream fails part-way, because a digest over part of something is
     *     a digest of a different thing
     */
    public static DigestValue of(InputStream stream) throws IOException {
        final MessageDigest algorithm = algorithm();
        final byte[] buffer = new byte[READ_BUFFER_BYTES];
        int read = stream.read(buffer);
        while (read >= 0) {
            algorithm.update(buffer, 0, read);
            read = stream.read(buffer);
        }
        return DigestValue.ofBytes(algorithm.digest());
    }

    private static MessageDigest algorithm() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (final NoSuchAlgorithmException absent) {
            // Every Java runtime this product supports carries this algorithm. A runtime that does
            // not is one nothing here can work on, and saying so is better than degrading.
            throw new IllegalStateException(ALGORITHM + " is not available on this runtime", absent);
        }
    }
}
