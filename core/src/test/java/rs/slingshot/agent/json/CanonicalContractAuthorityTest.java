// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;

/**
 * Four steps, in one order, and every way of getting the order wrong.
 *
 * <p>That the order cannot be skipped is asserted over the types rather than over a call: a value a
 * later step needs has no public constructor and no factory but the step before it, so there is no
 * sequence of calls that reaches the third step without the first. A test that only called them in
 * the right order would prove that the right order works, which is not the property.</p>
 */
final class CanonicalContractAuthorityTest {

    private static final Path REPOSITORY = repositoryRoot();

    private static final Path SCHEMAS = REPOSITORY.resolve("schemas");

    private static final Path FIXTURES =
            REPOSITORY.resolve("core/src/test/resources/fixtures/canonical-authentication");

    private static final BoundedDocumentReader.Bounds BOUNDS =
            BoundedDocumentReader.Bounds.from(contract());

    private static final String COMMAND = "add_component";

    private static final String ROLE = "arguments";

    @Test
    @DisplayName("a correct arrangement passes all four steps and yields the material an identity"
            + " is assembled from")
    void theWholeChainPasses() {
        final CanonicalContractAuthority.IdentityMaterial material =
                assertInstanceOf(CanonicalContractAuthority.Assembled.class,
                        believed(bytes("schema.json"), digest("schema.sha256"))
                                .permitting(COMMAND, ROLE),
                        "a correct arrangement was refused").material();
        assertEquals(COMMAND, material.commandName());
        assertEquals(ROLE, material.role());
        assertEquals(digest("schema.sha256"), material.roleDigest().rendered());
        assertEquals(committedContractDigest(), material.contractDigest().rendered());
    }

    @Test
    @DisplayName("altered contract bytes fail at the first step and nowhere else")
    void alteredContractBytesFailFirst() {
        final CanonicalContractAuthority.Outcome outcome = CanonicalContractAuthority.authenticate(
                bytes("altered-contract.json"), committedContractDigest());
        final CanonicalContractAuthority.Refused refused =
                assertInstanceOf(CanonicalContractAuthority.Refused.class, outcome,
                        "altered contract bytes authenticated");
        assertEquals(AuthenticationStep.CONTRACT_BYTES, refused.step());
        assertTrue(refused.detail().contains(committedContractDigest()), refused.detail());
    }

    @Test
    @DisplayName("a schema naming another contract fails at the second step")
    void anAnnotationNamingAnotherContractFailsSecond() {
        assertEquals(AuthenticationStep.SCHEMA_ANNOTATION,
                assertInstanceOf(CanonicalContractAuthority.Refused.class,
                        authority().annotated(bytes("schema-naming-another-contract.json"), BOUNDS),
                        "a schema naming another contract was accepted").step());
    }

    @Test
    @DisplayName("a schema whose own bytes changed fails at the third step")
    void alteredSchemaBytesFailThird() {
        final CanonicalContractAuthority.Refused refused =
                assertInstanceOf(CanonicalContractAuthority.Refused.class,
                        annotated(bytes("altered-schema.json")).believed(digest("schema.sha256")),
                        "a schema whose bytes changed was believed");
        assertEquals(AuthenticationStep.SCHEMA_DIGEST, refused.step());
        assertTrue(refused.detail().contains(digest("schema.sha256")), refused.detail());
    }

    @Test
    @DisplayName("a schema describing another command fails at the fourth step")
    void aSchemaForAnotherCommandFailsFourth() {
        final CanonicalContractAuthority.Refused refused =
                assertInstanceOf(CanonicalContractAuthority.Refused.class,
                        believed(bytes("schema-for-another-command.json"),
                                digest("schema-for-another-command.sha256"))
                                .permitting(COMMAND, ROLE),
                        "a schema for another command assembled an identity");
        assertEquals(AuthenticationStep.IDENTITY_ASSEMBLY, refused.step());
        assertTrue(refused.detail().contains("delete_page"), refused.detail());
    }

    @Test
    @DisplayName("what is committed for a schema being no digest at all fails at the third step")
    void aCommittedDigestThatIsNotOneFailsThird() {
        final CanonicalContractAuthority.Refused refused =
                assertInstanceOf(CanonicalContractAuthority.Refused.class,
                        annotated(bytes("schema.json")).believed("not a digest"),
                        "a schema was believed against something that is not a digest");
        assertEquals(AuthenticationStep.SCHEMA_DIGEST, refused.step());
        assertTrue(refused.detail().contains("not a digest"), refused.detail());
    }

    @Test
    @DisplayName("an arrangement broken twice reports the earlier step and stops there")
    void theEarlierFailureIsTheOneReported() {
        final CanonicalContractAuthority.Outcome outcome = CanonicalContractAuthority.authenticate(
                bytes("altered-contract.json"), committedContractDigest());
        assertEquals(AuthenticationStep.CONTRACT_BYTES,
                assertInstanceOf(CanonicalContractAuthority.Refused.class, outcome).step(),
                "a doubly broken arrangement reported the later failure");
        // And the later failure is a real one, which is what makes the first assertion mean
        // something: run the second step on its own and it refuses too.
        assertEquals(AuthenticationStep.SCHEMA_ANNOTATION,
                assertInstanceOf(CanonicalContractAuthority.Refused.class,
                        authority().annotated(bytes("schema-naming-another-contract.json"), BOUNDS))
                        .step());
    }

    @Test
    @DisplayName("no failure discloses a byte of what it authenticated")
    void nothingIsDisclosed() {
        final String schema = new String(bytes("schema.json"), StandardCharsets.UTF_8);
        final String detail = assertInstanceOf(CanonicalContractAuthority.Refused.class,
                annotated(bytes("altered-schema.json")).believed(digest("schema.sha256"))).detail();
        assertFalse(detail.contains(schema.substring(0, 40)),
                "a failure disclosed the bytes it was comparing: " + detail);
        assertTrue(detail.contains("digest"), detail);
    }

    @Test
    @DisplayName("no later step is reachable without the value the earlier one produces")
    void theOrderIsStructural() {
        assertOnlyProducedByItsOwnStep(CanonicalContractAuthority.AnnotatedSchema.class);
        assertOnlyProducedByItsOwnStep(CanonicalContractAuthority.RoleAuthority.class);
        assertOnlyProducedByItsOwnStep(CanonicalContractAuthority.class);
        assertEquals(1, Arrays.stream(CanonicalContractAuthority.class.getMethods())
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .filter(method -> !method.getDeclaringClass().equals(Object.class))
                        .count(),
                "there is a second way into the chain besides the first step");
    }

    private static void assertOnlyProducedByItsOwnStep(Class<?> produced) {
        Arrays.stream(produced.getDeclaredConstructors())
                .forEach(constructor -> assertTrue(isPrivate(constructor),
                        produced.getSimpleName() + " can be built without the step that produces"
                                + " it"));
        assertTrue(Arrays.stream(produced.getMethods())
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .noneMatch(method -> produced.equals(method.getReturnType())),
                produced.getSimpleName() + " has a factory that skips the step before it");
    }

    private static boolean isPrivate(Constructor<?> constructor) {
        return Modifier.isPrivate(constructor.getModifiers());
    }

    private static CanonicalContractAuthority.RoleAuthority believed(byte[] schema,
                                                                     String committedDigest) {
        return assertInstanceOf(CanonicalContractAuthority.Believed.class,
                annotated(schema).believed(committedDigest),
                "the schema was not believed").authority();
    }

    private static CanonicalContractAuthority.AnnotatedSchema annotated(byte[] schema) {
        return assertInstanceOf(CanonicalContractAuthority.Annotated.class,
                authority().annotated(schema, BOUNDS),
                "the schema does not name the contract this repository commits").schema();
    }

    private static CanonicalContractAuthority authority() {
        return assertInstanceOf(CanonicalContractAuthority.Authenticated.class,
                CanonicalContractAuthority.authenticate(committedContract(),
                        committedContractDigest()),
                "the committed contract did not authenticate").authority();
    }

    private static byte[] committedContract() {
        return read(SCHEMAS.resolve("command-canonical-json-1.json"));
    }

    private static String committedContractDigest() {
        return new String(read(SCHEMAS.resolve("command-canonical-json-1.sha256")),
                StandardCharsets.UTF_8).strip();
    }

    private static byte[] bytes(String fixture) {
        return read(FIXTURES.resolve(fixture));
    }

    private static String digest(String fixture) {
        return new String(bytes(fixture), StandardCharsets.UTF_8).strip();
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
