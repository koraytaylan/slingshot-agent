// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * A bound that bounds something, and two framings refused rather than resolved.
 *
 * <p>The stream that counts what was taken from it is what makes the first claim checkable: a
 * suite that only asserted the refusal would pass just as well against an implementation that read
 * the whole body first and then complained about it, which is a limit on nothing.</p>
 */
final class BoundedRequestBodyTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/request-body");

    private static final AgentContract CONTRACT = contract();

    @Test
    @DisplayName("a body inside the bound is read, and what comes back is what was sent")
    void abodyInsideTheBoundIsRead() {
        final byte[] submission = bytes(FIXTURES.resolve("a-submission.json"));
        assertArrayEquals(submission, assertInstanceOf(BoundedRequestBody.Read.class,
                        BoundedRequestBody.read(new ByteArrayInputStream(submission),
                                submission.length, CONTRACT))
                        .bytes(),
                "what came back is not what was sent");
    }

    @Test
    @DisplayName("a body at exactly the bound is read and one byte past it is refused")
    void thebooundHoldsAtBothSides() {
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_BODY_BYTES);
        assertInstanceOf(BoundedRequestBody.Read.class,
                BoundedRequestBody.read(new ByteArrayInputStream(new byte[(int) bound]),
                        bound, CONTRACT),
                "a body at exactly the bound was refused");
        final BoundedRequestBody.Refused refused = BoundedRequestBody.refusalIn(
                BoundedRequestBody.read(new ByteArrayInputStream(new byte[(int) bound + 1]),
                        bound + 1, CONTRACT)).orElseThrow();
        assertEquals(BoundedRequestBody.Refusal.PAST_THE_BOUND, refused.refusal());
        assertTrue(refused.detail().contains(String.valueOf(bound)), refused.detail());
    }

    @Test
    @DisplayName("a body past the bound is refused before the whole of it has been read")
    void abodyPastTheBoundIsRefusedBeforeItIsRead() throws IOException {
        final long bound = CONTRACT.value(ContractLimit.MAXIMUM_REQUEST_BODY_BYTES);
        try (Counting arriving = new Counting((int) bound * 4)) {
            assertEquals(BoundedRequestBody.Refusal.PAST_THE_BOUND, BoundedRequestBody.refusalIn(
                    BoundedRequestBody.read(arriving, bound * 4, CONTRACT)).orElseThrow()
                    .refusal());
            assertTrue(arriving.taken() <= bound + READ_AHEAD,
                    "the whole body was read before the bound was applied: " + arriving.taken()
                            + " bytes of " + bound * 4 + " were taken");
            assertFalse(arriving.taken() == bound * 4, "every byte was read anyway");
        }
    }

    /** How far past the bound one more read may take, which is one read's worth. */
    private static final long READ_AHEAD = rs.slingshot.agent.digest.Digest.READ_BUFFER_BYTES;

    /** A length a failing stream's caller declared, so the refusal is about the stream. */
    private static final long DECLARED = 16;

    @Test
    @DisplayName("a declared length that differs from what arrives is refused, in both directions")
    void adeclaredLengthThatDiffersIsRefused() {
        final byte[] submission = bytes(FIXTURES.resolve("a-submission.json"));
        assertEquals(BoundedRequestBody.Refusal.SHORTER_THAN_DECLARED, BoundedRequestBody.refusalIn(
                BoundedRequestBody.read(new ByteArrayInputStream(submission),
                        submission.length + 1, CONTRACT)).orElseThrow().refusal(),
                "a truncated body was read as though it were whole");
        assertEquals(BoundedRequestBody.Refusal.LONGER_THAN_DECLARED, BoundedRequestBody.refusalIn(
                BoundedRequestBody.read(new ByteArrayInputStream(submission),
                        submission.length - 1, CONTRACT)).orElseThrow().refusal(),
                "a body longer than its own declaration was read as though it agreed");
        assertInstanceOf(BoundedRequestBody.Read.class, BoundedRequestBody.read(
                new ByteArrayInputStream(submission), FramingPolicy.NO_LENGTH_DECLARED, CONTRACT),
                "a body whose length nothing declared was refused for disagreeing with nothing");
    }

    @Test
    @DisplayName("bytes that stop arriving are a refusal rather than a shorter body")
    void bytesThatStopArrivingAreArefusal() throws IOException {
        try (Failing stopped = new Failing()) {
            final BoundedRequestBody.Refused refused = BoundedRequestBody.refusalIn(
                    BoundedRequestBody.read(stopped, DECLARED, CONTRACT)).orElseThrow();
            assertEquals(BoundedRequestBody.Refusal.TRANSFER_FAILED, refused.refusal());
            assertEquals(0, refused.bytesRead());
        }
    }

    @Test
    @DisplayName("two framings together are refused rather than resolved")
    void twoframingsTogetherAreRefused() {
        assertEquals(FramingPolicy.Refusal.FRAMED_TWICE, FramingPolicy.refusalIn(
                new FramingPolicy(DECLARED, FramingPolicy.Chunked.FRAMED_IN_CHUNKS, "").read())
                .orElseThrow().refusal(),
                "a request framed twice was resolved rather than refused");
        assertEquals(FramingPolicy.Refusal.AN_UNREQUESTED_CODING, FramingPolicy.refusalIn(
                new FramingPolicy(DECLARED, FramingPolicy.Chunked.NOT_FRAMED_IN_CHUNKS, "gzip")
                        .read())
                .orElseThrow().refusal(),
                "a coding this side did not ask for was decoded rather than refused");
        assertEquals(FramingPolicy.Refusal.NO_FRAMING_AT_ALL, FramingPolicy.refusalIn(
                new FramingPolicy(FramingPolicy.NO_LENGTH_DECLARED,
                        FramingPolicy.Chunked.NOT_FRAMED_IN_CHUNKS, "").read()).orElseThrow()
                .refusal(), "a request that said nothing about its body was read anyway");
        assertEquals(DECLARED, assertInstanceOf(FramingPolicy.Framed.class,
                new FramingPolicy(DECLARED, FramingPolicy.Chunked.NOT_FRAMED_IN_CHUNKS, "").read())
                .declaredLength());
        assertEquals(FramingPolicy.Chunked.FRAMED_IN_CHUNKS,
                assertInstanceOf(FramingPolicy.Framed.class,
                        new FramingPolicy(FramingPolicy.NO_LENGTH_DECLARED,
                                FramingPolicy.Chunked.FRAMED_IN_CHUNKS, "").read()).chunked());
        assertEquals(3, FramingPolicy.Refusal.values().length, "a refusal was added or lost");
    }

    /** A stream that says how much of it was taken. */
    private static final class Counting extends InputStream {

        private final int length;

        private final AtomicLong taken = new AtomicLong();

        Counting(int length) {
            this.length = length;
        }

        @Override
        public int read() {
            return taken.get() >= length ? -1 : (int) taken.getAndIncrement() % 2;
        }

        @Override
        public int read(byte[] into, int from, int howMany) {
            if (taken.get() >= length) {
                return -1;
            }
            final int giving = (int) Math.min(howMany, length - taken.get());
            taken.addAndGet(giving);
            return giving;
        }

        long taken() {
            return taken.get();
        }
    }

    /** A stream that stops. */
    private static final class Failing extends InputStream {

        @Override
        public int read() throws IOException {
            throw new IOException("the caller went away");
        }

        @Override
        public int read(byte[] into, int from, int howMany) throws IOException {
            throw new IOException("the caller went away");
        }
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
