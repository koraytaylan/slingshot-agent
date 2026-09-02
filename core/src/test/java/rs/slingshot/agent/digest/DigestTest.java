// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.digest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one comparison every authentication in this repository ends in.
 *
 * <p>That the comparison examines every byte is asserted over the implementation rather than by
 * timing it: a timing assertion on a shared machine measures the machine, and would pass on a
 * comparison that returns early whenever the machine was busy enough to hide it.</p>
 */
final class DigestTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/digest");

    /** The digest of no bytes at all, which is a value rather than an absence. */
    private static final String EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("known content digests to its known value, including no content at all")
    void knownContentDigestsToItsKnownValue() {
        assertEquals(EMPTY, Digest.of(new byte[0]).rendered());
        assertEquals(committed("empty"), Digest.of(bytes("empty.bin")).rendered());
        assertEquals(committed("known"), Digest.of(bytes("known.bin")).rendered());
        assertEquals(committed("known").toLowerCase(java.util.Locale.ROOT), committed("known"),
                "the committed digest is not written the way this type renders one");
    }

    @Test
    @DisplayName("a rendering that is short, long, upper-case, or not hexadecimal is refused")
    void thefourWaysOfNotBeingADigestAreRefusedDistinctly() {
        assertEquals(DigestValue.Refusal.TOO_SHORT, refusalOf("too-short"));
        assertEquals(DigestValue.Refusal.TOO_LONG, refusalOf("too-long"));
        assertEquals(DigestValue.Refusal.NOT_LOWER_CASE, refusalOf("upper-case"));
        assertEquals(DigestValue.Refusal.NOT_HEXADECIMAL, refusalOf("not-hexadecimal"));
    }

    @Test
    @DisplayName("a digest of the wrong length cannot be held at all")
    void bytesThatAreNotADigestCannotBeHeld() {
        assertThrows(IllegalArgumentException.class, () -> DigestValue.ofBytes(new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> DigestValue.ofBytes(new byte[DigestValue.BYTE_LENGTH + 1]));
    }

    @Test
    @DisplayName("a difference in the first byte and one in the last are both differences")
    void aDifferenceAnywhereIsADifference() {
        final DigestValue known = held(committed("known"));
        assertTrue(known.matches(held(committed("known"))));
        assertFalse(known.matches(held(read("differs-in-the-first-byte.sha256"))));
        assertFalse(known.matches(held(read("differs-in-the-last-byte.sha256"))));
        assertEquals(known, held(committed("known")));
        assertNotEquals(known, held(read("differs-in-the-last-byte.sha256")));
        assertEquals(known.hashCode(), held(committed("known")).hashCode());
    }

    @Test
    @DisplayName("the comparison examines every byte, and no other equality is reachable")
    void theComparisonCannotReturnEarly() {
        final String source = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/digest/DigestValue.java"));
        assertTrue(source.contains("MessageDigest.isEqual(value, other.value)"),
                "the comparison is not the one that examines every byte");
        assertTrue(source.contains("return other instanceof final DigestValue digest"
                        + " && matches(digest);"),
                "equality is a second comparison rather than the same one");
        assertFalse(source.contains("Arrays.equals("),
                "an equality that returns on the first difference is reachable for this type");
        assertEquals(1, occurrences(source, "boolean matches("),
                "there is more than one comparison on this type");
    }

    @Test
    @DisplayName("a stream and the bytes it carries digest to the same value")
    void streamedAndWholeInputAgree() {
        final byte[] larger = bytes("larger-than-one-buffer.bin");
        assertTrue(larger.length > Digest.READ_BUFFER_BYTES,
                "the input is not larger than one read");
        assertEquals(Digest.of(larger).rendered(), streamed("larger-than-one-buffer.bin").rendered());
        assertEquals(committed("larger-than-one-buffer"), Digest.of(larger).rendered());
        assertEquals(EMPTY, streamed("empty.bin").rendered());
    }

    @Test
    @DisplayName("a committed resource yields no bytes until it has authenticated")
    void aCommittedResourceAuthenticatesFirst() {
        final CommittedResource.Outcome authentic =
                CommittedResource.authenticate(bytes("known.bin"), committed("known"));
        assertEquals(committed("known"), assertInstanceOf(CommittedResource.Loaded.class, authentic,
                "authentic bytes were refused").resource().digest().rendered());
        assertEquals("abc", new String(((CommittedResource.Loaded) authentic).resource().bytes(),
                StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("bytes that do not match, a digest that is not one, and an absent resource fail")
    void thethreeWaysAResourceProducesNoBytesAreDistinct() {
        assertEquals(CommittedResource.Failure.NOT_AUTHENTIC, failureOf(CommittedResource
                .authenticate(bytes("known.bin"), read("differs-in-the-last-byte.sha256"))));
        assertEquals(CommittedResource.Failure.NOT_A_DIGEST, failureOf(CommittedResource
                .authenticate(bytes("known.bin"), read("too-short.sha256"))));
        assertEquals(CommittedResource.Failure.NOT_EMBEDDED, failureOf(CommittedResource
                .load("/rs/slingshot/agent/digest/nothing-is-here", "/also-not-here")));
    }

    @Test
    @DisplayName("the contract embedded in this bundle authenticates through the same loader")
    void theEmbeddedContractAuthenticatesThroughTheSameLoader() {
        final CommittedResource.Outcome outcome = CommittedResource.load(
                "/rs/slingshot/agent/contract/agent-contract.toml",
                "/rs/slingshot/agent/contract/agent-contract.sha256");
        assertInstanceOf(CommittedResource.Loaded.class, outcome,
                "the embedded contract did not authenticate: " + outcome);
    }

    private static DigestValue.Refusal refusalOf(String fixture) {
        return assertInstanceOf(DigestValue.Refused.class,
                DigestValue.of(read(fixture + ".sha256")),
                fixture + " was held as a digest").refusal();
    }

    private static CommittedResource.Failure failureOf(CommittedResource.Outcome outcome) {
        return assertInstanceOf(CommittedResource.Refused.class, outcome,
                "bytes were handed over that should not have been").failure();
    }

    private static DigestValue held(String rendered) {
        return assertInstanceOf(DigestValue.Held.class, DigestValue.of(rendered),
                rendered + " is not a digest").digest();
    }

    private static long occurrences(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1L;
    }

    private static DigestValue streamed(String fixture) {
        try (InputStream stream = Files.newInputStream(FIXTURES.resolve(fixture))) {
            return Digest.of(stream);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String committed(String fixture) {
        return read(fixture + ".sha256");
    }

    private static String read(String fixture) {
        return read(FIXTURES.resolve(fixture));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static byte[] bytes(String fixture) {
        try {
            return Files.readAllBytes(FIXTURES.resolve(fixture));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
