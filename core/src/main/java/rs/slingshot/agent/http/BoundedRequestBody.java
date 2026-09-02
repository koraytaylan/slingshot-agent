// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * A request body read as it arrives, refused the moment the next byte would cross the bound.
 *
 * <p>A limit checked after a body is collected is a limit on nothing: whatever the limit says, the
 * bytes are already here and the memory is already spent. So this reads incrementally and stops at
 * the byte that would cross, which is the only arrangement where the bound bounds anything.</p>
 *
 * <p>A declared length that differs from what arrives is refused in both directions. Too few bytes
 * is a truncated request somebody would otherwise act on; too many is a sender whose framing this
 * side would otherwise have to choose between.</p>
 */
public final class BoundedRequestBody {

    /**
     * How much is read from the stream at a time.
     *
     * <p>The same read size the digest reader uses, because both are reading somebody else's bytes
     * a chunk at a time and there is no reason for this side to have two answers to that.</p>
     */
    private static final int READ_CHUNK_BYTES = rs.slingshot.agent.digest.Digest.READ_BUFFER_BYTES;

    private BoundedRequestBody() {
    }

    /** Why a body is not read. */
    public enum Refusal {
        /** It is larger than the bound, found at the byte that would have crossed it. */
        PAST_THE_BOUND,
        /** Fewer bytes arrived than the request declared. */
        SHORTER_THAN_DECLARED,
        /** More bytes arrived than the request declared. */
        LONGER_THAN_DECLARED,
        /** The bytes stopped arriving. */
        TRANSFER_FAILED
    }

    /** The result of reading a body. */
    public sealed interface Outcome permits Read, Refused {
    }

    /**
     * A body this side read whole.
     *
     * @param bytes what arrived
     */
    public record Read(byte[] bytes) implements Outcome {

        /** Holds bytes nothing can change afterwards. */
        public Read {
            bytes = bytes.clone();
        }

        /**
         * What arrived.
         *
         * @return the bytes, as a copy nothing else holds
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * One this side did not.
     *
     * @param refusal why not
     * @param detail what was observed, naming the numbers where two were compared
     * @param bytesRead how many bytes had arrived when it was refused
     */
    public record Refused(Refusal refusal, String detail, long bytesRead) implements Outcome {
    }

    /**
     * Reads a body, stopping at the byte that would cross the bound the contract declares.
     *
     * @param body the bytes as they arrive
     * @param declaredLength how long the request said it is, or {@link FramingPolicy#NO_LENGTH_DECLARED}
     * @param contract the authenticated contract, which declares the bound
     * @return what arrived, or the one reason it was not read
     */
    public static Outcome read(InputStream body, long declaredLength, AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_REQUEST_BODY_BYTES);
        final ByteArrayOutputStream held = new ByteArrayOutputStream();
        final byte[] chunk = new byte[READ_CHUNK_BYTES];
        long read = 0;
        try {
            int arrived = body.read(chunk);
            while (arrived >= 0) {
                read = read + arrived;
                if (read > bound) {
                    return new Refused(Refusal.PAST_THE_BOUND, "this body is past the bound of "
                            + bound + " bytes, found at the byte that crossed it", read);
                }
                held.write(chunk, 0, arrived);
                arrived = body.read(chunk);
            }
        } catch (final IOException stopped) {
            return new Refused(Refusal.TRANSFER_FAILED,
                    "the bytes stopped arriving: " + stopped.getMessage(), read);
        }
        return againstTheDeclaration(held.toByteArray(), declaredLength);
    }

    private static Outcome againstTheDeclaration(byte[] bytes, long declaredLength) {
        if (declaredLength == FramingPolicy.NO_LENGTH_DECLARED) {
            return new Read(bytes);
        }
        if (bytes.length < declaredLength) {
            return new Refused(Refusal.SHORTER_THAN_DECLARED, bytes.length + " bytes arrived and "
                    + declaredLength + " were declared", bytes.length);
        }
        if (bytes.length > declaredLength) {
            return new Refused(Refusal.LONGER_THAN_DECLARED, bytes.length + " bytes arrived and "
                    + declaredLength + " were declared", bytes.length);
        }
        return new Read(bytes);
    }

    /**
     * The one reason a body was not read, where it was not.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where the body was read
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
