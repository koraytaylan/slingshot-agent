// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Four values that together mean one durable thing, and every way of meaning something else.
 *
 * <p>The generation is the one member with a rule about time: a store that was rebuilt gets a later
 * incarnation, and a document presenting an earlier one is presenting itself as belonging to a
 * store that no longer exists. That is proved here as a refusal naming both numbers, because a
 * caller told only that a generation was refused cannot tell a replay from a typo.</p>
 */
final class OperationIdentityTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/operation-identity");

    private static final Path SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/identity/operation.json");

    private static final AgentContract CONTRACT = contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    @Test
    @DisplayName("a document carrying all four members is an identity, and each is readable")
    void aCompleteDocumentIsAnIdentity() {
        final OperationIdentity identity = held("complete.json");
        assertEquals(1, identity.generation().number());
        assertEquals(64, identity.identifier().rendered().length());
        assertEquals("revision-2026-09-01", identity.environmentRevision());
        assertEquals(identity, held("complete.json"));
        assertEquals(identity.hashCode(), held("complete.json").hashCode());
        assertNotEquals(identity, held("generation-later.json"));
    }

    @Test
    @DisplayName("each of the four members absent is refused, naming that member")
    void eachAbsentMemberIsRefusedNamingIt() {
        OperationIdentity.MEMBERS.forEach(member -> {
            final IdentityRefusal refusal = refusal("absent-" + member.replace('_', '-') + ".json");
            assertEquals(IdentityRefusal.Failure.MEMBER_ABSENT, refusal.failure(), member);
            assertEquals(member, refusal.member());
        });
    }

    @Test
    @DisplayName("a generation of zero is refused, one is accepted, and a decrease names both")
    void theGenerationOnlyEverMovesForward() {
        assertEquals(IdentityRefusal.Failure.OUT_OF_RANGE, refusal("generation-zero.json").failure());
        assertEquals(1, held("generation-one.json").generation().number());
        final EventStoreGeneration later = held("generation-later.json").generation();
        final EventStoreGeneration first = held("generation-one.json").generation();
        assertInstanceOf(EventStoreGeneration.Held.class, later.notBefore(first),
                "a later generation was refused as a decrease");
        final EventStoreGeneration.Refused refused = assertInstanceOf(
                EventStoreGeneration.Refused.class, first.notBefore(later),
                "a store went back to an earlier incarnation and nothing refused it");
        assertEquals(EventStoreGeneration.Refusal.BEFORE_ONE_ALREADY_SEEN, refused.refusal());
        assertTrue(refused.detail().contains("1") && refused.detail().contains("7"),
                refused.detail());
    }

    @Test
    @DisplayName("a generation that is not a whole number is refused as its own thing")
    void aGenerationThatIsNotANumberIsRefused() {
        assertEquals(IdentityRefusal.Failure.NOT_TEXT,
                refusal("generation-that-is-not-a-number.json").failure());
    }

    @Test
    @DisplayName("an identifier that is upper-case, short, long, or not hexadecimal is refused")
    void everyIdentifierShapeIsRefused() {
        List.of("upper-case", "short", "long", "not-hexadecimal").forEach(shape -> {
            final IdentityRefusal refusal = refusal("identifier-" + shape + ".json");
            assertEquals(OperationIdentity.IDENTIFIER, refusal.member(), shape);
            assertEquals(IdentityRefusal.Failure.NOT_A_DIGEST, refusal.failure(), shape);
        });
        assertEquals(OperationIdentity.TARGET_DIGEST,
                refusal("target-digest-upper-case.json").member());
    }

    @Test
    @DisplayName("an identifier past the contract's own bound is refused before its shape is read")
    void anIdentifierPastTheBoundIsRefused() {
        final AgentOperationIdentifier.Refused refused =
                assertInstanceOf(AgentOperationIdentifier.Refused.class,
                        AgentOperationIdentifier.of("a".repeat(200), CONTRACT),
                        "an identifier longer than the transport carries was held");
        assertEquals(AgentOperationIdentifier.Refusal.PAST_THE_BOUND, refused.refusal());
    }

    @Test
    @DisplayName("the revision holds at exactly its bound and one byte past it, and empty fails")
    void theRevisionBoundHoldsAtBothSides() {
        assertEquals(256, held("at-the-revision-bound.json").environmentRevision().length());
        assertEquals(IdentityRefusal.Failure.TOO_LONG,
                refusal("past-the-revision-bound.json").failure());
        assertEquals(IdentityRefusal.Failure.EMPTY, refusal("empty-revision.json").failure());
    }

    @Test
    @DisplayName("a fifth member and a document that is not an object are refused distinctly")
    void anythingElseIsRefused() {
        assertEquals(IdentityRefusal.Failure.MEMBER_UNKNOWN, refusal("a-fifth-member.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_A_DOCUMENT, refusal("not-an-object.json").failure());
    }

    @Test
    @DisplayName("the committed schema and this model name the same members in both directions")
    void theSchemaAndTheModelAgree() {
        assertEquals(List.copyOf(OperationIdentity.MEMBERS).stream().sorted().toList(),
                declaredMembers().stream().sorted().toList(),
                "the schema and the model disagree about what an operation identity carries");
        assertEquals(256L,
                CONTRACT.value(rs.slingshot.agent.contract.ContractLimit
                        .MAXIMUM_SELECTED_ENVIRONMENT_REVISION_BYTES),
                "the bound this side enforces is not the one the schema declares");
    }

    private static List<String> declaredMembers() {
        final DocumentValue.Mapping schema = assertInstanceOf(DocumentValue.Mapping.class,
                document(new String(read(SCHEMA), StandardCharsets.UTF_8).strip()
                        .getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(assertInstanceOf(DocumentValue.Mapping.class,
                schema.member("properties").orElseThrow()).members().keySet());
    }

    private static OperationIdentity held(String fixture) {
        return assertInstanceOf(OperationIdentity.Held.class, outcome(fixture),
                fixture + " was refused").identity();
    }

    private static IdentityRefusal refusal(String fixture) {
        return assertInstanceOf(OperationIdentity.Refused.class, outcome(fixture),
                fixture + " was held as an identity").refusal();
    }

    private static OperationIdentity.Outcome outcome(String fixture) {
        return OperationIdentity.of(document(read(FIXTURES.resolve(fixture))), CONTRACT);
    }

    private static DocumentValue document(byte[] bytes) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(bytes, DOCUMENT_BOUNDS),
                "the fixture is not a document this reader accepts").value();
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
