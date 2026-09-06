// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.stream.DefaultStreamTicker;

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
        try (ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
            final Thread worker = new Thread(runnable, "slingshot-request-transfer");
            worker.setDaemon(true);
            return worker;
        })) {
            final long started = DefaultStreamTicker.monotonicNanoseconds();
            long moved = started;
            read = transfer(new Transfer(io, body, chunk, started, moved, contract, bound, held));
            return againstTheDeclaration(held.toByteArray(), declaredLength);
        } catch (final BoundExceeded exceeded) {
            return new Refused(Refusal.PAST_THE_BOUND, "this body is past the bound of "
                    + exceeded.bound + " bytes, found at the byte that crossed it", exceeded.amount);
        } catch (final IOException stopped) {
            return new Refused(Refusal.TRANSFER_FAILED,
                    "the bytes stopped arriving: " + stopped.getMessage(), read);
        }
    }

    private record Transfer(ExecutorService io, InputStream body, byte[] chunk, long started,
                             long moved, AgentContract contract, long bound,
                             ByteArrayOutputStream held) { }

    private static long transfer(Transfer transfer) throws IOException {
        long total = 0;
        int arrived = read(transfer.io(), transfer.body(), transfer.chunk(), transfer.started(),
                transfer.moved(), transfer.contract());
        while (arrived >= 0) {
            total = total + arrived;
            if (total > transfer.bound()) {
                throw new BoundExceeded(total, transfer.bound());
            }
            transfer.held().write(transfer.chunk(), 0, arrived);
            final long moved = DefaultStreamTicker.monotonicNanoseconds();
            arrived = read(transfer.io(), transfer.body(), transfer.chunk(), transfer.started(),
                    moved, transfer.contract());
        }
        return total;
    }

    private static final class BoundExceeded extends IOException {
        private static final long serialVersionUID = 1L;
        private final long amount;
        private final long bound;

        private BoundExceeded(long amount, long bound) {
            this.amount = amount;
            this.bound = bound;
        }
    }

    private static int read(ExecutorService io, InputStream body, byte[] chunk,
                            long started, long moved, AgentContract contract) throws IOException {
        final Future<Integer> pending = io.submit(() -> body.read(chunk));
        final long total = TimeUnit.MILLISECONDS.toNanos(TransferDeadlines.totalMilliseconds(contract));
        final long idle = TimeUnit.MILLISECONDS.toNanos(TransferDeadlines.idleMilliseconds(contract));
        final long timeout = Math.max(1, Math.min(
                total - (DefaultStreamTicker.monotonicNanoseconds() - started),
                idle - (DefaultStreamTicker.monotonicNanoseconds() - moved)));
        try {
            return pending.get(timeout, TimeUnit.NANOSECONDS);
        } catch (final TimeoutException timeoutFailure) {
            pending.cancel(true);
            closeAfterTimeout(body);
            throw withCause("request body exceeded its transfer deadline", timeoutFailure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw withCause("request body was interrupted", interrupted);
        } catch (final ExecutionException failed) {
            throw withCause("request body read failed", failed);
        }
    }

    private static void closeAfterTimeout(InputStream body) {
        try {
            body.close();
        } catch (final IOException ignored) {
            // The deadline has already ended the request.
        }
    }

    private static IOException withCause(String message, Throwable cause) {
        final IOException failure = new IOException(message);
        failure.initCause(cause);
        return failure;
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
