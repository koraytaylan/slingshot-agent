// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The idempotency key, derived here and compared with what a client sent.
 *
 * <p>The expected values in the vector file were computed from the written derivation by something
 * that is not this code, so a defect here shows up as a mismatch rather than as two implementations
 * agreeing on the same mistake. Every vector differs from the base in exactly one way, which is
 * what makes a failure name the member that broke.</p>
 */
final class SubmittedCommandDigestTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path VECTORS = REPOSITORY.resolve(
            "core/src/test/resources/fixtures/submitted-command-digest/vectors.json");

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(contract());

    private static final CommandContractIdentity.Bounds IDENTITY_BOUNDS =
            CommandContractIdentity.Bounds.from(contract());

    @Test
    @DisplayName("every vector derives exactly the digest and the key it declares")
    void everyVectorDerivesItsOwnValues() {
        vectors().forEach(vector -> {
            assertEquals(text(vector, "submitted_command_digest"),
                    digestOf(vector).value().rendered(),
                    "the vector for " + text(vector, "note") + " derived another digest");
            assertEquals(text(vector, "idempotency_key"),
                    bindingOf(vector).keyFor(digestOf(vector)).rendered(),
                    "the vector for " + text(vector, "note") + " derived another key");
        });
    }

    @Test
    @DisplayName("no two vectors that differ in anything derive the same key")
    void everyDifferenceIsADifferentKey() {
        final List<String> keys = vectors().stream()
                .map(vector -> bindingOf(vector).keyFor(digestOf(vector)).rendered())
                .toList();
        assertEquals(keys.size(), Set.copyOf(keys).size(),
                "two submissions that differ derived one key");
    }

    @Test
    @DisplayName("changing one member alone changes the digest, member by member")
    void eachMemberAloneMovesTheDigest() {
        final String base = text(vectors().getFirst(), "submitted_command_digest");
        vectors().stream()
                .skip(1)
                .filter(vector -> !text(vector, "note").startsWith("the same manifest"))
                .filter(vector -> !text(vector, "note").startsWith("a manifest of kind"))
                .forEach(vector -> assertNotEquals(base,
                        text(vector, "submitted_command_digest"),
                        "the vector for " + text(vector, "note") + " did not move the digest"));
    }

    @Test
    @DisplayName("the manifest moves the key without moving the digest, kind by kind")
    void theManifestFoldsInAfterTheDigest() {
        final DocumentValue.Mapping base = vectors().getFirst();
        vectors().stream()
                .filter(vector -> text(vector, "note").startsWith("a manifest of kind")
                        || text(vector, "note").startsWith("the same manifest"))
                .forEach(vector -> {
                    assertEquals(digestOf(base).value().rendered(),
                            digestOf(vector).value().rendered(),
                            "a manifest moved the contract digest, which it must not");
                    assertNotEquals(bindingOf(base).keyFor(digestOf(base)).rendered(),
                            bindingOf(vector).keyFor(digestOf(vector)).rendered(),
                            "the vector for " + text(vector, "note") + " did not move the key");
                });
        assertEquals(3, ArtifactManifestKind.values().length, "a fourth manifest kind appeared");
        assertEquals(List.of("empty", "load", "package"),
                List.of(ArtifactManifestKind.EMPTY.spelling(), ArtifactManifestKind.LOAD.spelling(),
                        ArtifactManifestKind.PACKAGE.spelling()),
                "a manifest kind is spelled differently here than in the derivation");
        assertTrue(ArtifactManifestKind.named("neither").isEmpty(),
                "a kind nobody declared was read as one");
    }

    @Test
    @DisplayName("two fields that run together are kept apart by the separator")
    void theSeparatorKeepsFieldsApart() {
        final DocumentValue.Mapping base = vectors().getFirst();
        final DocumentValue.Mapping running = vectors().stream()
                .filter(vector -> text(vector, "note").startsWith("two fields that run together"))
                .findFirst()
                .orElseThrow();
        assertNotEquals(digestOf(base).value().rendered(), digestOf(running).value().rendered(),
                "two arrangements of the same bytes derived one digest, so the separator does"
                        + " nothing");
        assertEquals(0, SubmittedCommandDigest.FIELD_SEPARATOR,
                "the separator is not the byte no field can carry");
    }

    @Test
    @DisplayName("a key that is not the derived one is refused without either key appearing")
    void aMismatchingKeyIsRefusedWithoutDisclosure() {
        final SubmittedCommandDigest derived = digestOf(vectors().getFirst());
        assertInstanceOf(SubmittedCommandDigest.Matched.class,
                derived.compare(derived.value().rendered()), "the derived key was refused");
        final String other = text(vectors().get(1), "submitted_command_digest");
        final SubmittedCommandDigest.Refused refused =
                assertInstanceOf(SubmittedCommandDigest.Refused.class, derived.compare(other),
                        "a key from another submission was accepted");
        assertEquals(SubmittedCommandDigest.Refusal.NOT_THE_DERIVED_KEY, refused.refusal());
        assertFalse(refused.detail().contains(other), refused.detail());
        assertFalse(refused.detail().contains(derived.value().rendered()), refused.detail());
        assertEquals(SubmittedCommandDigest.Refusal.NOT_A_DIGEST,
                assertInstanceOf(SubmittedCommandDigest.Refused.class,
                        derived.compare("not a key")).refusal());
    }

    @Test
    @DisplayName("the type says outright which two values it does not cover")
    void theTypeSaysWhatItDoesNotCover() {
        // Read as one line: what the type says is a sentence, and where a sentence wraps is a
        // property of the margin rather than of what it says.
        final String source = new String(read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/identity/SubmittedCommandDigest.java")),
                StandardCharsets.UTF_8).replace("\n", " ").replace(" * ", " ")
                .replaceAll("\\s+", " ");
        assertTrue(source.contains("What this digest does not cover"),
                "the type does not say what it leaves out");
        assertTrue(source.contains("target identity digest") && source.contains("environment"
                + " revision"), "the type does not name both values it leaves out");
        assertTrue(source.contains("beside this digest rather than folded into it"),
                "the type does not say where those two are compared instead");
    }

    private static SubmittedCommandDigest digestOf(DocumentValue.Mapping vector) {
        return SubmittedCommandDigest.derive(identityOf(vector),
                digest(text(vector, "canonical_json_contract_digest")),
                digest(text(vector, "transport_contract_digest")),
                text(vector, "canonical_arguments").getBytes(StandardCharsets.UTF_8));
    }

    private static SubmissionBinding bindingOf(DocumentValue.Mapping vector) {
        final DocumentValue.Mapping manifest = assertInstanceOf(DocumentValue.Mapping.class,
                vector.member("manifest").orElseThrow());
        return new SubmissionBinding(
                ArtifactManifestKind.named(text(manifest, "kind")).orElseThrow(),
                whole(manifest, "artifact_rows"), whole(manifest, "artifact_bytes"));
    }

    private static CommandContractIdentity identityOf(DocumentValue.Mapping vector) {
        return assertInstanceOf(CommandContractIdentity.Held.class,
                CommandContractIdentity.of(vector.member("identity").orElseThrow(),
                        IDENTITY_BOUNDS),
                "a vector carries an identity that is not one").identity();
    }

    private static List<DocumentValue.Mapping> vectors() {
        final DocumentValue.Mapping document = assertInstanceOf(DocumentValue.Mapping.class,
                assertInstanceOf(BoundedDocumentReader.Read.class,
                        BoundedDocumentReader.read(read(VECTORS), DOCUMENT_BOUNDS),
                        "the vector file is not a document this reader accepts").value());
        return assertInstanceOf(DocumentValue.Sequence.class,
                document.member("vector").orElseThrow()).items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Mapping.class, item))
                .toList();
    }

    private static String text(DocumentValue.Mapping mapping, String member) {
        return assertInstanceOf(DocumentValue.Text.class,
                mapping.member(member).orElseThrow(
                        () -> new IllegalStateException("a vector declares no " + member))).value();
    }

    private static long whole(DocumentValue.Mapping mapping, String member) {
        return assertInstanceOf(DocumentValue.Whole.class,
                mapping.member(member).orElseThrow()).value();
    }

    private static DigestValue digest(String rendered) {
        return assertInstanceOf(DigestValue.Held.class, DigestValue.of(rendered),
                rendered + " is not a digest").digest();
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
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
