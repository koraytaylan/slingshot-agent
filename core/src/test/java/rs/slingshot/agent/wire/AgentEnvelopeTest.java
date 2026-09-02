// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.digest.DigestValue;
import rs.slingshot.agent.identity.DocumentProvenance;
import rs.slingshot.agent.identity.IdentityRefusal;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One document's two required halves, and the one shape a refusal has.
 *
 * <p>The secret corpus is checked against every code's sentence rather than against one of them,
 * because a message that discloses is a message somebody wrote later — and the assertion has to be
 * about the set rather than about the examples that existed when it was written.</p>
 */
final class AgentEnvelopeTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/envelope-and-error");

    private static final Path ERROR_SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/common/error.json");

    private static final Path ENVELOPE_SCHEMA =
            REPOSITORY.resolve("schemas/agent-protocol/common/envelope.json");

    private static final AgentContract CONTRACT = contract();

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(CONTRACT);

    private static final AgentError.Bounds ERROR_BOUNDS = AgentError.Bounds.from(CONTRACT);

    private static final DocumentProvenance.ThisBuild BUILD = new DocumentProvenance.ThisBuild(
            digest(AgentContract.transportContractDigest()),
            digest(committed("schemas/command-canonical-json-1.sha256")));

    /** Values that must never appear in anything a caller is told. */
    private static final List<String> SECRET_SHAPED = List.of(
            "admin:admin", "Bearer ey", "-----BEGIN", "password=", "jcr:", "/content/",
            "/home/users/", "rs.slingshot.agent", ".java", "Exception", "at java.");

    @Test
    @DisplayName("an envelope carrying provenance and an operation is read whole")
    void anAcceptedEnvelopeIsReadWhole() {
        final AgentEnvelope envelope = held("accepted.json");
        assertEquals("query_paths", envelope.provenance().commandContract().wireName());
        assertEquals(1, envelope.operation().generation().number());
        assertEquals(envelope, held("accepted.json"));
        assertEquals(envelope.hashCode(), held("accepted.json").hashCode());
    }

    @Test
    @DisplayName("absent provenance, absent operation, and a third member are three refusals")
    void thethreeEnvelopeRefusalsAreDistinct() {
        assertEquals(IdentityRefusal.Failure.MEMBER_ABSENT,
                refusal("absent-provenance.json").failure());
        assertEquals(AgentEnvelope.PROVENANCE, refusal("absent-provenance.json").member());
        assertEquals(AgentEnvelope.OPERATION, refusal("absent-operation.json").member());
        assertEquals(IdentityRefusal.Failure.MEMBER_UNKNOWN,
                refusal("a-third-member.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_A_DOCUMENT,
                refusal("not-an-object.json").failure());
    }

    @Test
    @DisplayName("the committed envelope schema and this model name the same members")
    void theEnvelopeSchemaAndTheModelAgree() {
        assertEquals(AgentEnvelope.MEMBERS.stream().sorted().toList(),
                declaredMembers(ENVELOPE_SCHEMA).stream().sorted().toList());
    }

    @Test
    @DisplayName("an error document is read, and an unknown code is refused rather than passed on")
    void anUnknownCodeIsRefused() {
        assertEquals(ErrorCode.UNAUTHENTICATED, error("error-accepted.json").code());
        assertEquals(AgentError.Refusal.UNKNOWN_CODE,
                errorRefusal("error-unknown-code.json").refusal());
        assertEquals(AgentError.Refusal.MEMBER_ABSENT,
                errorRefusal("error-absent-message.json").refusal());
        assertEquals(AgentError.Refusal.MEMBER_UNKNOWN,
                errorRefusal("error-a-third-member.json").refusal());
        assertEquals(AgentError.Refusal.EMPTY,
                errorRefusal("error-empty-message.json").refusal());
        assertEquals(AgentError.Refusal.NOT_A_DOCUMENT,
                errorRefusal("error-not-an-object.json").refusal());
    }

    @Test
    @DisplayName("both error bounds hold at exactly their own length and one byte past it")
    void bothErrorBoundsHoldAtBothSides() {
        assertEquals(ERROR_BOUNDS.messageBytes(),
                error("error-message-at-the-bound.json").message().length());
        assertEquals(AgentError.Refusal.TOO_LONG,
                errorRefusal("error-message-past-the-bound.json").refusal());
        assertEquals(AgentError.Refusal.TOO_LONG,
                errorRefusal("error-code-past-the-bound.json").refusal());
        assertEquals(4096L, CONTRACT.value(ContractLimit.MAXIMUM_AGENT_ERROR_MESSAGE_BYTES));
        assertEquals(64L, CONTRACT.value(ContractLimit.MAXIMUM_AGENT_ERROR_CODE_BYTES));
    }

    @Test
    @DisplayName("the closed code set is exactly the one the committed schema declares")
    void theCodeSetIsTheCommittedOne() {
        assertEquals(ErrorCode.spellings(), declaredCodes(),
                "this build and its committed schema disagree about the codes that exist");
    }

    @Test
    @DisplayName("no code's message names a path, a class, an address, or anything secret-shaped")
    void noMessageDisclosesAnything() {
        Arrays.stream(ErrorCode.values()).forEach(code -> {
            final String message = AgentError.of(code).message();
            assertEquals(code.sentence(), message);
            SECRET_SHAPED.forEach(shape -> assertFalse(message.contains(shape),
                    code.spelling() + " discloses " + shape + ": " + message));
            assertFalse(message.contains("/"),
                    code.spelling() + " names something addressed: " + message);
            assertTrue(message.length() < ERROR_BOUNDS.messageBytes(),
                    code.spelling() + " carries a message past the bound");
        });
    }

    @Test
    @DisplayName("there is no way to put a caller's value into a message at all")
    void aMessageCannotBeAssembled() {
        // The strongest form of "a secret passed as a parameter does not appear": there is no
        // parameter. Nothing on either type takes a value and answers a message, so no path,
        // name, or submitted byte can be interpolated into one.
        final List<Method> rendering = Arrays.stream(ErrorCode.class.getMethods())
                .filter(method -> method.getReturnType().equals(String.class))
                .filter(method -> method.getParameterCount() > 0)
                .toList();
        assertEquals(List.of(), rendering, "a code renders a message from something it was given");
        assertEquals(1, Arrays.stream(AgentError.class.getMethods())
                        .filter(method -> "of".equals(method.getName()))
                        .filter(method -> method.getParameterCount() == 1)
                        .count(),
                "an error can be built from something other than a code alone");
        assertTrue(Arrays.stream(AgentError.class.getMethods())
                        .filter(method -> "of".equals(method.getName()))
                        .allMatch(method -> method.getParameterTypes()[0].equals(ErrorCode.class)),
                "an error can be built from a value a caller supplied");
    }

    private static List<String> declaredCodes() {
        final DocumentValue.Mapping schema = mapping(ERROR_SCHEMA);
        final DocumentValue.Mapping properties = assertInstanceOf(DocumentValue.Mapping.class,
                schema.member("properties").orElseThrow());
        final DocumentValue.Mapping code = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member("code").orElseThrow());
        return assertInstanceOf(DocumentValue.Sequence.class, code.member("enum").orElseThrow())
                .items().stream()
                .map(item -> assertInstanceOf(DocumentValue.Text.class, item).value())
                .sorted()
                .toList();
    }

    private static List<String> declaredMembers(Path schema) {
        return List.copyOf(assertInstanceOf(DocumentValue.Mapping.class,
                mapping(schema).member("properties").orElseThrow()).members().keySet());
    }

    private static DocumentValue.Mapping mapping(Path schema) {
        return assertInstanceOf(DocumentValue.Mapping.class,
                document(new String(read(schema), StandardCharsets.UTF_8).strip()
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static AgentEnvelope held(String fixture) {
        return assertInstanceOf(AgentEnvelope.Held.class,
                AgentEnvelope.read(document(read(FIXTURES.resolve(fixture))), BUILD, CONTRACT),
                fixture + " was refused").envelope();
    }

    private static IdentityRefusal refusal(String fixture) {
        return assertInstanceOf(AgentEnvelope.Refused.class,
                AgentEnvelope.read(document(read(FIXTURES.resolve(fixture))), BUILD, CONTRACT),
                fixture + " was read as an envelope").refusal();
    }

    private static AgentError error(String fixture) {
        return assertInstanceOf(AgentError.Held.class,
                AgentError.read(document(read(FIXTURES.resolve(fixture))), ERROR_BOUNDS),
                fixture + " was refused").error();
    }

    private static AgentError.Refused errorRefusal(String fixture) {
        return assertInstanceOf(AgentError.Refused.class,
                AgentError.read(document(read(FIXTURES.resolve(fixture))), ERROR_BOUNDS),
                fixture + " was read as an error");
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
