// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.CommittedResource;

/**
 * The bytes two independent implementations have to agree on, proved against the client's own
 * vectors and this side's.
 *
 * <p>The shared file is the client's, carried in unchanged: every spelling it accepts must survive
 * the round trip byte for byte, and every spelling it refuses at the byte layer must come back
 * different — which is the same statement as "the canonical form has one spelling", checked from
 * both directions. The vectors this side adds are the ones the client had no reason to write down,
 * each carrying its input, the exact bytes it must produce, and what it proves.</p>
 */
final class CanonicalByteWriterTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path SCHEMAS = REPOSITORY.resolve("schemas");

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/canonical-bytes");

    private static final BoundedDocumentReader.Bounds BOUNDS =
            BoundedDocumentReader.Bounds.from(contract());

    /** The layer of the client's own vector file whose refusals are about these bytes. */
    private static final String BYTE_LAYER = "canonical_bytes";

    @Test
    @DisplayName("the canonical form's own contract authenticates before a vector is run")
    void theContractAuthenticatesFirst() {
        final CommittedResource.Outcome outcome = CommittedResource.authenticate(
                bytes(SCHEMAS.resolve("command-canonical-json-1.json")),
                text(SCHEMAS.resolve("command-canonical-json-1.sha256")).strip());
        assertInstanceOf(CommittedResource.Loaded.class, outcome,
                "the canonical contract did not authenticate: " + outcome);
        assertEquals(CanonicalByteWriter.FORMAT, formatNamedBy(
                bytes(SCHEMAS.resolve("command-canonical-json-1.json"))),
                "this side writes a form the contract does not name");
    }

    @Test
    @DisplayName("every spelling the client's own vectors accept survives the round trip exactly")
    void theSharedVectorsHoldByteForByte() {
        final List<DocumentValue.Mapping> vectors = section(
                SCHEMAS.resolve("command-canonical-json-vectors.json"), "raw_bytes");
        assertTrue(vectors.size() > 30, "the shared vector file lost most of its rows");
        vectors.forEach(CanonicalByteWriterTest::assertSharedVector);
    }

    @Test
    @DisplayName("every vector this side adds produces exactly the bytes it declares")
    void theLocalVectorsHoldByteForByte() {
        final List<DocumentValue.Mapping> vectors = section(FIXTURES.resolve("vectors.json"),
                "vector");
        assertEquals(21, vectors.size(), "the local vector file lost a row");
        vectors.forEach(vector -> assertEquals(member(vector, "expected"), canonical(vector),
                "the vector proving " + member(vector, "note") + " produced other bytes"));
    }

    @Test
    @DisplayName("a vector whose expected bytes are wrong fails, naming the vector")
    void aWrongExpectationIsCaught() {
        final DocumentValue.Mapping wrong =
                section(FIXTURES.resolve("wrong-expectation.json"), "vector").getFirst();
        assertFalse(member(wrong, "expected").equals(canonical(wrong)),
                "a vector expecting the wrong bytes was not caught");
        assertTrue(member(wrong, "note").contains("one character"),
                "the vector that was caught is not the one that was meant to be");
    }

    @Test
    @DisplayName("ordering, number form, and escaping are each isolated by a pair of vectors")
    void eachRuleIsProvedOnItsOwn() {
        assertEquals("{\"a\":2,\"b\":1}", canonicalOf("{\"b\":1,\"a\":2}"));
        assertEquals("{\"a\":2,\"b\":1}", canonicalOf("{\"a\":2,\"b\":1}"));
        assertEquals("{\"n\":7}", canonicalOf("{\"n\":007}"));
        assertEquals("{\"n\":7}", canonicalOf("{\"n\":7}"));
        assertEquals("{\"s\":\"A\"}", canonicalOf("{\"s\":\"\\u0041\"}"));
        assertEquals("{\"s\":\"A\"}", canonicalOf("{\"s\":\"A\"}"));
    }

    @Test
    @DisplayName("a value the form cannot carry is refused with its position and no partial output")
    void anUnrepresentableValueIsRefused() {
        final DocumentValue half = new DocumentValue.Mapping(
                new java.util.LinkedHashMap<>(java.util.Map.of("a",
                        new DocumentValue.Text("\ud800"))));
        final CanonicalRefusal refusal = assertInstanceOf(CanonicalByteWriter.Refused.class,
                CanonicalByteWriter.write(half), "half a character was written").refusal();
        assertEquals(CanonicalRefusal.Failure.NOT_A_WELL_FORMED_STRING, refusal.failure());
        assertEquals("/a", refusal.pointer());
        assertTrue(refusal.rendered().contains("/a"), refusal.rendered());
        final DocumentValue namedHalf = new DocumentValue.Mapping(
                new java.util.LinkedHashMap<>(java.util.Map.of("\udc00",
                        new DocumentValue.Whole(1))));
        assertEquals(CanonicalRefusal.Failure.NOT_A_WELL_FORMED_NAME,
                assertInstanceOf(CanonicalByteWriter.Refused.class,
                        CanonicalByteWriter.write(namedHalf),
                        "half a character was written as a name").refusal().failure());
    }

    @Test
    @DisplayName("canonical input converges, and so does the same value written any other way")
    void theRoundTripConverges() {
        final String canonical = "{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"x\"}}";
        assertEquals(canonical, canonicalOf(canonical));
        assertEquals(canonical, canonicalOf(" { \"c\" : { \"d\" : \"x\" } , \"b\" : [ 1 , 2 ] ,"
                + " \"a\" : 1 }"));
        assertEquals(canonical, canonicalOf(canonicalOf(canonical)));
    }

    private static void assertSharedVector(DocumentValue.Mapping vector) {
        final String spelling = member(vector, "spelling");
        final String note = member(vector, "note");
        final boolean accepted = flag(vector, "accepted");
        final Optional<String> written = written(spelling);
        if (written.isEmpty()) {
            assertFalse(accepted, "the vector for " + note + " was refused before it was written");
            return;
        }
        if (accepted) {
            assertEquals(spelling, written.get(), "the vector for " + note + " changed under a"
                    + " round trip, which means these bytes were not canonical after all");
            return;
        }
        if (BYTE_LAYER.equals(member(vector, "layer"))) {
            assertFalse(spelling.equals(written.get()), "the vector for " + note
                    + " is not canonical and survived the round trip unchanged");
            return;
        }
        // A vector refused by a later layer is canonical at this one: its bytes are the form's own,
        // and what is wrong with it is what it says rather than how it is spelled.
        assertEquals(spelling, written.get(), "the vector for " + note + " is refused by a later"
                + " layer and its bytes are not canonical either");
    }

    private static Optional<String> written(String spelling) {
        final BoundedDocumentReader.Outcome read =
                BoundedDocumentReader.read(spelling.getBytes(StandardCharsets.UTF_8), BOUNDS);
        if (read instanceof BoundedDocumentReader.Refused) {
            return Optional.empty();
        }
        return Optional.of(rendered(((BoundedDocumentReader.Read) read).value()));
    }

    private static String canonicalOf(String spelling) {
        return written(spelling).orElseThrow(
                () -> new IllegalStateException(spelling + " was refused before it was written"));
    }

    private static String canonical(DocumentValue.Mapping vector) {
        return canonicalOf(member(vector, "input"));
    }

    private static String rendered(DocumentValue value) {
        return assertInstanceOf(CanonicalByteWriter.Written.class,
                CanonicalByteWriter.write(value), "the value could not be written").rendered();
    }

    private static List<DocumentValue.Mapping> section(Path file, String name) {
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                        BoundedDocumentReader.read(bytes(file), BOUNDS),
                        file + " is not a document this reader accepts").value());
        return assertInstanceOf(DocumentValue.Sequence.class,
                document.member(name).orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Mapping.class, item))
                .toList();
    }

    private static String member(DocumentValue.Mapping vector, String name) {
        return assertInstanceOf(DocumentValue.Text.class,
                vector.member(name).orElseThrow(
                        () -> new IllegalStateException("a vector declares no " + name))).value();
    }

    private static boolean flag(DocumentValue.Mapping vector, String name) {
        return assertInstanceOf(DocumentValue.Flag.class, vector.member(name).orElseThrow())
                .value() == DocumentValue.Truth.TRUE;
    }

    private static String formatNamedBy(byte[] contract) {
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                        BoundedDocumentReader.read(contract, BOUNDS),
                        "the canonical contract is not a document this reader accepts").value());
        return assertInstanceOf(DocumentValue.Text.class,
                document.member("format").orElseThrow()).value();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String text(Path file) {
        return new String(bytes(file), StandardCharsets.UTF_8);
    }

    private static AgentContract contract() {
        return assertInstanceOf(AgentContract.Loaded.class, AgentContract.load(),
                "the contract did not authenticate").contract();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
