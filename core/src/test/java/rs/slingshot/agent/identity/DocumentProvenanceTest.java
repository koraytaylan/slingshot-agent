// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A claim a document makes about which contracts it means, compared with what this build means.
 *
 * <p>The format is compared to one exact constant, and that there is no other comparison is
 * asserted over the source rather than by trying spellings: a suite that tried three near misses
 * would pass on an implementation that accepted the fourth.</p>
 */
final class DocumentProvenanceTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/document-provenance");

    private static final Path SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/common/provenance.json");

    private static final AgentContract CONTRACT = contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    private static final CommandContractIdentity.Bounds IDENTITY_BOUNDS =
            CommandContractIdentity.Bounds.from(CONTRACT);

    private static final DocumentProvenance.ThisBuild BUILD = new DocumentProvenance.ThisBuild(
            digest(AgentContract.transportContractDigest()),
            digest(committed("schemas/command-canonical-json-1.sha256")));

    @Test
    @DisplayName("provenance naming the contracts this build means is accepted whole")
    void acceptedProvenanceCarriesTheCommandContract() {
        final DocumentProvenance provenance = held("accepted.json");
        assertEquals("query_paths", provenance.commandContract().wireName());
        assertEquals(AgentContract.transportContractDigest(),
                provenance.transportContractDigest().rendered());
        assertEquals(committed("schemas/command-canonical-json-1.sha256"),
                provenance.canonicalContractDigest().rendered());
        assertEquals(provenance, held("accepted.json"));
        assertEquals(provenance.hashCode(), held("accepted.json").hashCode());
    }

    @Test
    @DisplayName("a format that is anything but the one constant is refused, version suffix"
            + " included")
    void everyOtherFormatIsRefused() {
        List.of("another-format", "another-product", "no-version").forEach(spelling ->
                assertEquals(IdentityRefusal.Failure.FORMAT_NOT_EXACT,
                        refusal("format-" + spelling + ".json").failure(), spelling));
    }

    @Test
    @DisplayName("each of the four members absent is refused, naming that member")
    void eachAbsentMemberIsRefusedNamingIt() {
        DocumentProvenance.MEMBERS.forEach(member -> {
            final IdentityRefusal refusal = refusal("absent-" + member.replace('_', '-') + ".json");
            assertEquals(IdentityRefusal.Failure.MEMBER_ABSENT, refusal.failure(), member);
            assertEquals(member, refusal.member());
        });
    }

    @Test
    @DisplayName("a transport mismatch and a canonical mismatch are two refusals naming both"
            + " values")
    void thetwoContractMismatchesAreDistinct() {
        final IdentityRefusal transport = refusal("another-transport-contract.json");
        assertEquals(IdentityRefusal.Failure.TRANSPORT_CONTRACT_MISMATCH, transport.failure());
        assertTrue(transport.detail().contains(AgentContract.transportContractDigest()),
                transport.detail());
        final IdentityRefusal canonical = refusal("another-canonical-contract.json");
        assertEquals(IdentityRefusal.Failure.CANONICAL_CONTRACT_MISMATCH, canonical.failure());
        assertTrue(canonical.detail().contains(
                committed("schemas/command-canonical-json-1.sha256")), canonical.detail());
    }

    @Test
    @DisplayName("provenance whose command contract is incomplete is refused as that contract's"
            + " own failure")
    void anIncompleteCommandContractIsRefused() {
        final IdentityRefusal refusal = refusal("command-contract-missing-a-field.json");
        assertEquals(IdentityRefusal.Failure.MEMBER_ABSENT, refusal.failure());
        assertEquals(CommandContractIdentity.WIRE_NAME, refusal.member());
    }

    @Test
    @DisplayName("a fifth member and a document that is not an object are refused distinctly")
    void anythingElseIsRefused() {
        assertEquals(IdentityRefusal.Failure.MEMBER_UNKNOWN,
                refusal("a-fifth-member.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_A_DOCUMENT,
                refusal("not-an-object.json").failure());
    }

    @Test
    @DisplayName("the format is compared exactly, and no other comparison exists on the type")
    void thereIsNoRangeComparison() {
        final String source = new String(read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/identity/DocumentProvenance.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("!FORMAT.equals(format.get())"),
                "the format is not compared to the one constant");
        final List<String> aboutTheFormat = source.lines()
                .filter(line -> line.contains("format") || line.contains("FORMAT"))
                .filter(line -> !line.strip().startsWith("*"))
                .toList();
        List.of("startsWith", "compareTo", "matches(", "regionMatches", "endsWith", "substring")
                .forEach(comparison -> assertTrue(aboutTheFormat.stream()
                                .noneMatch(line -> line.contains(comparison)),
                        "a " + comparison + " comparison exists on the format"));
    }

    @Test
    @DisplayName("the committed schema and this model name the same members in both directions")
    void theSchemaAndTheModelAgree() {
        assertEquals(DocumentProvenance.MEMBERS.stream().sorted().toList(),
                declaredMembers().stream().sorted().toList(),
                "the schema and the model disagree about what provenance carries");
    }

    private static List<String> declaredMembers() {
        final DocumentValue.Mapping schema = assertInstanceOf(DocumentValue.Mapping.class,
                document(new String(read(SCHEMA), StandardCharsets.UTF_8).strip()
                        .getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(assertInstanceOf(DocumentValue.Mapping.class,
                schema.member("properties").orElseThrow()).members().keySet());
    }

    private static DocumentProvenance held(String fixture) {
        return assertInstanceOf(DocumentProvenance.Held.class, outcome(fixture),
                fixture + " was refused").provenance();
    }

    private static IdentityRefusal refusal(String fixture) {
        return assertInstanceOf(DocumentProvenance.Refused.class, outcome(fixture),
                fixture + " was held as provenance").refusal();
    }

    private static DocumentProvenance.Outcome outcome(String fixture) {
        return DocumentProvenance.of(document(read(FIXTURES.resolve(fixture))), BUILD,
                IDENTITY_BOUNDS);
    }

    private static DocumentValue document(byte[] bytes) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes, DOCUMENT_BOUNDS),
                "the fixture is not a document this reader accepts").value();
    }

    private static DigestValue digest(String rendered) {
        return assertInstanceOf(DigestValue.Held.class, DigestValue.of(rendered),
                rendered + " is not a digest").digest();
    }

    private static String committed(String path) {
        return new String(read(REPOSITORY.resolve(path)), StandardCharsets.UTF_8).strip();
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
