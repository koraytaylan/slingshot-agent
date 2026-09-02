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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * All five fields, and every way a document can carry fewer or more than five.
 *
 * <p>The bounds this side enforces are compared with the client's own committed schema here rather
 * than read from it at run time: a bound read from a document is a bound whoever sends the document
 * could choose, and a bound nobody compares is two numbers waiting to differ.</p>
 */
final class CommandContractIdentityTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/command-contract-identity");

    private static final Path SCHEMA = REPOSITORY.resolve(
            "schemas/agent-protocol/identity/command-contract.json");

    private static final BoundedDocumentReader.Bounds DOCUMENT_BOUNDS =
            BoundedDocumentReader.Bounds.from(contract());

    private static final CommandContractIdentity.Bounds BOUNDS =
            CommandContractIdentity.Bounds.from(contract());

    @Test
    @DisplayName("a document carrying all five fields is an identity, and each field is readable")
    void aCompleteDocumentIsAnIdentity() {
        final CommandContractIdentity identity = held("complete.json");
        assertEquals("query_paths", identity.wireName());
        assertEquals("1.0.0", identity.contractVersion());
        assertEquals(64, identity.limitsDigest().rendered().length());
        assertNotEquals(identity.argumentSchemaDigest().rendered(),
                identity.resultSchemaDigest().rendered());
        assertEquals(identity, held("complete.json"));
        assertEquals(identity.hashCode(), held("complete.json").hashCode());
    }

    @Test
    @DisplayName("each of the five fields absent is refused, naming that field")
    void eachAbsentFieldIsRefusedNamingIt() {
        CommandContractIdentity.MEMBERS.forEach(member -> {
            final IdentityRefusal refusal =
                    refusal("absent-" + member.replace('_', '-') + ".json");
            assertEquals(IdentityRefusal.Failure.MEMBER_ABSENT, refusal.failure(),
                    "a document without " + member + " was not refused for its absence");
            assertEquals(member, refusal.member());
        });
    }

    @Test
    @DisplayName("each of the five fields differing makes two identities different")
    void eachDifferingFieldMakesADifferentIdentity() {
        final CommandContractIdentity complete = held("complete.json");
        CommandContractIdentity.MEMBERS.forEach(member -> assertNotEquals(complete,
                held("differing-" + member.replace('_', '-') + ".json"),
                "two identities differing in " + member + " compared equal"));
    }

    @Test
    @DisplayName("a sixth member is refused rather than read around")
    void aSixthMemberIsRefused() {
        final IdentityRefusal refusal = refusal("a-sixth-member.json");
        assertEquals(IdentityRefusal.Failure.MEMBER_UNKNOWN, refusal.failure());
        assertEquals("command_kind", refusal.member());
        assertTrue(refusal.rendered().contains("MEMBER_UNKNOWN at command_kind"),
                refusal.rendered());
        assertFalse(refusal.rendered().contains("query_paths"),
                "a refusal read a submitted value back: " + refusal.rendered());
    }

    @Test
    @DisplayName("both length bounds hold at exactly their own length and one byte past it")
    void bothLengthBoundsHoldAtBothSides() {
        assertEquals(BOUNDS.wireNameBytes(), held("at-the-name-bound.json").wireName().length());
        assertEquals(IdentityRefusal.Failure.TOO_LONG, refusal("past-the-name-bound.json").failure());
        assertEquals(BOUNDS.contractVersionBytes(),
                held("at-the-version-bound.json").contractVersion().length());
        assertEquals(IdentityRefusal.Failure.TOO_LONG,
                refusal("past-the-version-bound.json").failure());
    }

    @Test
    @DisplayName("the bounds this side enforces are the ones the client's own schema declares")
    void theBoundsAreTheClientsOwn() {
        assertEquals(BOUNDS.wireNameBytes(),
                declaredMaximum(CommandContractIdentity.WIRE_NAME));
        assertEquals(BOUNDS.contractVersionBytes(),
                declaredMaximum(CommandContractIdentity.CONTRACT_VERSION));
    }

    @Test
    @DisplayName("a digest member that is upper-case, short, long, or not hexadecimal is refused")
    void everyDigestShapeIsRefused() {
        List.of("upper-case", "short", "long", "not-hexadecimal").forEach(shape -> {
            final IdentityRefusal refusal = refusal("digest-" + shape + ".json");
            assertEquals(CommandContractIdentity.LIMITS_DIGEST, refusal.member(), shape);
            assertTrue(refusal.failure() == IdentityRefusal.Failure.NOT_A_DIGEST
                            || refusal.failure() == IdentityRefusal.Failure.TOO_LONG,
                    shape + " was refused as " + refusal.failure());
        });
    }

    @Test
    @DisplayName("an empty field, a field that is not text, and a document that is not an object"
            + " are refused distinctly")
    void everyOtherShapeIsRefusedDistinctly() {
        assertEquals(IdentityRefusal.Failure.EMPTY, refusal("empty-name.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_TEXT,
                refusal("name-that-is-not-text.json").failure());
        assertEquals(IdentityRefusal.Failure.NOT_A_DOCUMENT,
                refusal("not-an-object.json").failure());
    }

    @Test
    @DisplayName("nothing on this type compares less than all five fields")
    void thereIsNoPartialComparison() {
        final List<Method> comparisons = Arrays.stream(CommandContractIdentity.class.getMethods())
                .filter(method -> !method.getDeclaringClass().equals(Object.class))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType().equals(boolean.class))
                .toList();
        assertEquals(List.of("equals"), comparisons.stream().map(Method::getName).toList(),
                "there is a comparison on this type besides the total one");
    }

    private static long declaredMaximum(String member) {
        // The committed schema is a file people read, so it ends in a newline; the reader refuses
        // a byte after the document because a document arriving over the wire has none.
        final DocumentValue.Mapping schema = assertInstanceOf(DocumentValue.Mapping.class,
                mapping(new String(read(SCHEMA), StandardCharsets.UTF_8).strip()
                        .getBytes(StandardCharsets.UTF_8)));
        final DocumentValue.Mapping properties = assertInstanceOf(DocumentValue.Mapping.class,
                schema.member("properties").orElseThrow());
        final DocumentValue.Mapping property = assertInstanceOf(DocumentValue.Mapping.class,
                properties.member(member).orElseThrow());
        return assertInstanceOf(DocumentValue.Whole.class,
                property.member("maxLength").orElseThrow()).value();
    }

    private static CommandContractIdentity held(String fixture) {
        return assertInstanceOf(CommandContractIdentity.Held.class, outcome(fixture),
                fixture + " was refused").identity();
    }

    private static IdentityRefusal refusal(String fixture) {
        return assertInstanceOf(CommandContractIdentity.Refused.class, outcome(fixture),
                fixture + " was held as an identity").refusal();
    }

    private static CommandContractIdentity.Outcome outcome(String fixture) {
        return CommandContractIdentity.of(mapping(read(FIXTURES.resolve(fixture))), BOUNDS);
    }

    private static DocumentValue mapping(byte[] document) {
        return assertInstanceOf(BoundedDocumentReader.Read.class,
                BoundedDocumentReader.read(document, DOCUMENT_BOUNDS),
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
