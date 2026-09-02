// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The supported deployment matrix, and the six ways a row can be wrong.
 *
 * <p>The rule worth reading twice is the last one: no row can declare itself proved. There is no
 * field for evidence, so a document carrying one is refused as an unknown key rather than
 * believed — which is what keeps a table of declarations from quietly becoming a table of
 * claims.</p>
 */
final class DeploymentMatrixTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/deployment-matrix");

    private static final Path MATRIX = REPOSITORY.resolve("support/deployments.toml");

    @Test
    @DisplayName("the matrix parses into the exact declared rows, in the file's own order")
    void theMatrixParsesIntoItsDeclaredRows() {
        final DeploymentMatrix matrix = loaded(MATRIX);
        assertEquals(List.of("aem-cloud-service", "aem-6-5-lts"), matrix.identifiers());
        final DeploymentMatrix.DeploymentRow cloud = matrix.row("aem-cloud-service").orElseThrow();
        assertEquals("Adobe Experience Manager as a Cloud Service", cloud.product());
        assertEquals(21L, cloud.javaRuntime());
        assertEquals(DeploymentMatrix.Clustering.CLUSTERED, cloud.clustering());
        assertEquals("b", cloud.interoperabilityTier());
        assertTrue(cloud.builtFor());
        assertFalse(matrix.row("aem-6-5-lts").orElseThrow().builtFor());
        assertTrue(matrix.row("nothing-like-this").isEmpty());
    }

    @Test
    @DisplayName("exactly one row is the one this product is built for")
    void exactlyOneRowIsTheBuiltForRow() {
        assertEquals("aem-cloud-service", loaded(MATRIX).builtFor().identifier());
        assertEquals(DeploymentMatrix.Failure.BUILT_FOR_NOT_SINGULAR,
                refusal("two-built-for-rows.toml").failure());
        assertEquals(DeploymentMatrix.Failure.BUILT_FOR_NOT_SINGULAR,
                refusal("no-built-for-row.toml").failure());
    }

    @Test
    @DisplayName("a row whose Java runtime is below the release level is refused naming both")
    void aRuntimeBelowTheBytecodeTargetIsRefused() {
        final DeploymentMatrix.Refused refused = refusal("runtime-below-target.toml");
        assertEquals(DeploymentMatrix.Failure.RUNTIME_BELOW_TARGET, refused.failure());
        assertTrue(refused.detail().contains("aem-6-5-lts"), refused.detail());
        assertTrue(refused.detail().contains("17"), refused.detail());
        assertTrue(refused.detail().contains("21"), refused.detail());
    }

    @Test
    @DisplayName("an unknown tier, a duplicate row, and a missing field are refused distinctly")
    void theThreeStructuralRefusalsAreDistinct() {
        assertEquals(DeploymentMatrix.Failure.UNKNOWN_TIER, refusal("unknown-tier.toml").failure());
        assertEquals(DeploymentMatrix.Failure.DUPLICATE_ROW, refusal("duplicate-row.toml").failure());
        assertEquals(DeploymentMatrix.Failure.DOCUMENT, refusal("missing-field.toml").failure());
        assertTrue(refusal("missing-field.toml").detail().contains("MISSING_KEY"));
        assertTrue(refusal("missing-field.toml").detail().contains("request_window_milliseconds"));
    }

    @Test
    @DisplayName("a row cannot declare itself proved, and a value outside its type is refused")
    void noRowDeclaresItsOwnEvidence() {
        final DeploymentMatrix.Refused evidence = refusal("self-declared-evidence.toml");
        assertEquals(DeploymentMatrix.Failure.DOCUMENT, evidence.failure());
        assertTrue(evidence.detail().contains("UNKNOWN_KEY"), evidence.detail());
        assertTrue(evidence.detail().contains("evidence"), evidence.detail());
        assertTrue(refusal("out-of-type.toml").detail().contains("WRONG_TYPE"));
    }

    @Test
    @DisplayName("the accepted fixture and the committed matrix agree")
    void theAcceptedFixtureIsTheCommittedMatrix() {
        assertEquals(loaded(MATRIX).identifiers(), loaded(FIXTURES.resolve("accepted.toml")).identifiers());
    }

    @Test
    @DisplayName("every declared row provides a runtime at or above the bytecode target")
    void everyRowSatisfiesTheBytecodeContract() {
        assertEquals("", loaded(MATRIX).againstBytecodeContract().render());
    }

    @Test
    @DisplayName("the smallest declared request window is the one every budget sits under")
    void theSmallestRequestWindowIsTheBindingOne() {
        final DeploymentMatrix matrix = loaded(MATRIX);
        assertEquals(60_000L, matrix.smallestRequestWindowMilliseconds());
        assertTrue(matrix.rows().stream()
                        .allMatch(row -> row.requestWindowMilliseconds()
                                >= matrix.smallestRequestWindowMilliseconds()),
                "a row declares a window below the smallest one");
    }

    private static DeploymentMatrix loaded(Path file) {
        final DeploymentMatrix.Outcome outcome = DeploymentMatrix.load(file);
        return assertInstanceOf(DeploymentMatrix.Loaded.class, outcome,
                file + " was refused: " + outcome).matrix();
    }

    private static DeploymentMatrix.Refused refusal(String fixture) {
        final DeploymentMatrix.Outcome outcome = DeploymentMatrix.load(FIXTURES.resolve(fixture));
        return assertInstanceOf(DeploymentMatrix.Refused.class, outcome,
                fixture + " was accepted where it must be refused");
    }
}
