// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * Both sides of every bound, and every way a document can fail to be one.
 *
 * <p>The two length bounds are exercised on documents this suite builds rather than on committed
 * ones. A file of exactly two mebibytes committed to prove a bound is two mebibytes every reader of
 * this repository clones forever, and the input it stands for is stated more precisely here than a
 * file could state it.</p>
 */
final class BoundedDocumentReaderTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/bounded-document");

    private static final BoundedDocumentReader.Bounds BOUNDS =
            BoundedDocumentReader.Bounds.from(contract());

    @Test
    @DisplayName("the bounds are the contract's own, and this package declares none of them")
    void everyBoundComesFromTheContract() {
        final AgentContract contract = contract();
        assertEquals(contract.value(ContractLimit.MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES),
                BOUNDS.documentBytes());
        assertEquals(contract.value(ContractLimit.MAXIMUM_DOCUMENT_NESTING_DEPTH),
                BOUNDS.nestingDepth());
        assertEquals(contract.value(ContractLimit.MAXIMUM_DOCUMENT_OBJECT_MEMBERS),
                BOUNDS.objectMembers());
        assertEquals(contract.value(ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES),
                BOUNDS.stringBytes());
    }

    @Test
    @DisplayName("a document that satisfies every bound is read into the value it states")
    void anAcceptedDocumentIsReadWhole() {
        final DocumentValue.Mapping read = mapping(fixture("accepted.json"));
        assertEquals(List.of("a", "b", "c"), List.copyOf(read.members().keySet()));
        assertEquals(new DocumentValue.Whole(1), read.member("a").orElseThrow());
        final DocumentValue.Sequence items =
                assertInstanceOf(DocumentValue.Sequence.class, read.member("b").orElseThrow());
        assertEquals(List.of(new DocumentValue.Flag(DocumentValue.Truth.TRUE),
                        new DocumentValue.Flag(DocumentValue.Truth.FALSE),
                        new DocumentValue.Nothing(), new DocumentValue.Text("text")),
                items.items());
        assertEquals(new DocumentValue.Whole(-2),
                assertInstanceOf(DocumentValue.Mapping.class, read.member("c").orElseThrow())
                        .member("d").orElseThrow());
    }

    @Test
    @DisplayName("the document bound holds at exactly its own length and one byte past it")
    void theDocumentBoundHoldsAtBothSides() {
        // Stated rather than taken from the contract, because a document of exactly two mebibytes
        // built inside a suite proves the same thing as one of exactly forty-eight bytes and costs
        // every run of it two mebibytes. That the production bound is the contract's own is what
        // the first assertion in this file is about.
        final long stated = 48;
        final BoundedDocumentReader.Bounds narrow = new BoundedDocumentReader.Bounds(stated,
                BOUNDS.nestingDepth(), BOUNDS.objectMembers(), BOUNDS.stringBytes());
        final byte[] atTheBound = padded(stated);
        assertEquals(stated, atTheBound.length);
        assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(atTheBound, narrow),
                "a document of exactly the bound was refused");
        assertEquals(DocumentRefusal.Failure.DOCUMENT_BYTES,
                refusalOf(padded(stated + 1), stated + 1, narrow).failure());
    }

    @Test
    @DisplayName("the nesting bound holds at exactly its own depth and one level past it")
    void theNestingBoundHoldsAtBothSides() {
        assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(fixture("nesting-at-the-bound.json"), BOUNDS),
                "a document nested to exactly the bound was refused");
        assertEquals(DocumentRefusal.Failure.NESTING_DEPTH,
                failureOf(fixture("nesting-past-the-bound.json")));
    }

    @Test
    @DisplayName("the member bound holds at exactly its own count and one member past it")
    void theMemberBoundHoldsAtBothSides() {
        assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(fixture("members-at-the-bound.json"), BOUNDS),
                "an object carrying exactly the bound was refused");
        assertEquals(DocumentRefusal.Failure.OBJECT_MEMBERS,
                failureOf(fixture("members-past-the-bound.json")));
    }

    @Test
    @DisplayName("the string bound holds at exactly its own length and one byte past it")
    void theStringBoundHoldsAtBothSides() {
        assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(withStringOf(BOUNDS.stringBytes()), BOUNDS),
                "a string of exactly the bound was refused");
        assertEquals(DocumentRefusal.Failure.STRING_BYTES,
                failureOf(withStringOf(BOUNDS.stringBytes() + 1)));
    }

    @Test
    @DisplayName("a duplicate member, trailing bytes, an unterminated input, and a length that"
            + " does not match are four distinct refusals")
    void thefourStructuralRefusalsAreDistinct() {
        assertEquals(DocumentRefusal.Failure.DUPLICATE_MEMBER,
                failureOf(fixture("duplicate-member.json")));
        assertEquals(DocumentRefusal.Failure.TRAILING_BYTES,
                failureOf(fixture("trailing-bytes.json")));
        assertEquals(DocumentRefusal.Failure.UNTERMINATED, failureOf(fixture("unterminated.json")));
        final byte[] shorter = fixture("accepted.json");
        assertEquals(DocumentRefusal.Failure.LENGTH_MISMATCH,
                failureOf(shorter, shorter.length + 1));
    }

    @Test
    @DisplayName("a number that is not whole and a byte that cannot be there are refused too")
    void aNonWholeNumberAndAMisplacedByteAreRefused() {
        assertEquals(DocumentRefusal.Failure.NOT_WHOLE, failureOf(fixture("not-whole.json")));
        assertEquals(DocumentRefusal.Failure.MALFORMED, failureOf(fixture("malformed.json")));
    }

    @Test
    @DisplayName("every escape the form permits is read back as the character it names")
    void everyEscapeIsRead() {
        final DocumentValue.Text read = assertInstanceOf(DocumentValue.Text.class,
                mapping(fixture("escapes.json")).member("a").orElseThrow());
        assertEquals("quote \" solidus \\ slash / back \b feed \f line \n return \r tab \t"
                + " control \u0007 letter A", read.value());
    }

    @Test
    @DisplayName("an escape that names nothing, half a character, or no hexadecimal is refused")
    void anEscapeThatNamesNothingIsRefused() {
        assertEquals(DocumentRefusal.Failure.MALFORMED, failureOf(fixture("unknown-escape.json")));
        assertEquals(DocumentRefusal.Failure.MALFORMED,
                failureOf(fixture("escape-not-hexadecimal.json")));
        assertEquals(DocumentRefusal.Failure.MALFORMED,
                failureOf(fixture("escape-half-a-character.json")));
        assertEquals(DocumentRefusal.Failure.UNTERMINATED,
                failureOf(fixture("unterminated-escape.json")));
    }

    @Test
    @DisplayName("a broken literal, an unquoted name, and a missing comma are all refused")
    void everyMisplacedByteIsRefused() {
        assertEquals(DocumentRefusal.Failure.MALFORMED, failureOf(fixture("broken-literal.json")));
        assertEquals(DocumentRefusal.Failure.MALFORMED,
                failureOf(fixture("name-that-is-not-a-string.json")));
        assertEquals(DocumentRefusal.Failure.MALFORMED,
                failureOf(fixture("array-without-a-comma.json")));
        assertEquals(DocumentRefusal.Failure.MALFORMED,
                failureOf(fixture("object-without-a-comma.json")));
    }

    @Test
    @DisplayName("the refusal is the first one, taken before the rest of the input is read")
    void theFirstRefusalIsTheOneReported() {
        final byte[] document = fixture("refuses-before-the-end.json");
        final BoundedDocumentReader.Bounds narrow =
                new BoundedDocumentReader.Bounds(BOUNDS.documentBytes(), BOUNDS.nestingDepth(),
                        BOUNDS.objectMembers(), 100);
        final DocumentRefusal refusal = refusalOf(document, document.length, narrow);
        assertEquals(DocumentRefusal.Failure.STRING_BYTES, refusal.failure(),
                "the refusal reported is not the first one the input crosses");
        assertTrue(refusal.position() < document.length,
                "the whole input was consumed before the refusal was taken: " + refusal.position());
    }

    @Test
    @DisplayName("a refusal names the bound and where reading stopped")
    void aRefusalSaysWhatAndWhere() {
        final DocumentRefusal refusal =
                refusalOf(fixture("duplicate-member.json"), fixture("duplicate-member.json").length,
                        BOUNDS);
        assertTrue(refusal.rendered().contains("DUPLICATE_MEMBER"), refusal.rendered());
        assertTrue(refusal.rendered().contains("at byte " + refusal.position()),
                refusal.rendered());
        assertTrue(refusal.position() > 0, "a refusal was reported before a byte was read");
    }

    @Test
    @DisplayName("nothing that was read is reachable after a refusal")
    void noPartialValueIsReachable() {
        final RecordComponent[] carried = BoundedDocumentReader.Refused.class.getRecordComponents();
        assertEquals(1, carried.length);
        assertEquals(DocumentRefusal.class, carried[0].getType());
        assertTrue(Arrays.stream(BoundedDocumentReader.class.getMethods())
                        .filter(method -> !method.getDeclaringClass().equals(Object.class))
                        .noneMatch(BoundedDocumentReaderTest::answersAValue),
                "the reader hands back a value outside the outcome that says it was read");
        assertTrue(Arrays.stream(DocumentRefusal.class.getMethods())
                        .noneMatch(BoundedDocumentReaderTest::answersAValue),
                "a refusal hands back part of the document it refused");
    }

    private static boolean answersAValue(Method method) {
        return DocumentValue.class.isAssignableFrom(method.getReturnType());
    }

    private static byte[] withStringOf(long length) {
        return ("{\"a\":\"" + "x".repeat((int) length) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A document of exactly the length asked for, padded inside a string so the length is the
     * document's rather than an accident of what it says.
     */
    private static byte[] padded(long length) {
        final int scaffolding = "{\"a\":\"\"}".length();
        return ("{\"a\":\"" + "x".repeat((int) length - scaffolding) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static DocumentValue.Mapping mapping(byte[] document) {
        return assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                        BoundedDocumentReader.read(document, BOUNDS),
                        "the document was refused").value());
    }

    private static DocumentRefusal.Failure failureOf(byte[] document) {
        return refusalOf(document, document.length, BOUNDS).failure();
    }

    private static DocumentRefusal.Failure failureOf(byte[] document, long declaredLength) {
        return refusalOf(document, declaredLength, BOUNDS).failure();
    }

    private static DocumentRefusal refusalOf(byte[] document, long declaredLength,
                                             BoundedDocumentReader.Bounds bounds) {
        try {
            return assertInstanceOf(BoundedDocumentReader.Refused.class,
                    BoundedDocumentReader.read(new ByteArrayInputStream(document), declaredLength,
                            bounds),
                    "the document was read and should not have been").refusal();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static byte[] fixture(String name) {
        try {
            return Files.readAllBytes(FIXTURES.resolve(name));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
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
