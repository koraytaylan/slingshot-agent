// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The nullability contract, and the eight shapes that break it.
 *
 * <p>The one worth reading twice is the last: a null reaching an argument position through a local
 * variable. A check that matched the token would miss it, and the defect it causes is the same
 * defect — which is why the rule follows the assignment.</p>
 */
final class NullabilityPolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/nullability");

    /** The package this repository's annotations come from, which no bundle may import. */
    private static final String ANNOTATION_PACKAGE = "org.jetbrains.annotations";

    @Test
    @DisplayName("every main source in this repository holds the contract")
    void thisRepositoryHoldsTheContract() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("an unannotated parameter and an unannotated return are refused distinctly")
    void undeclaredNullnessIsRefused() {
        assertRule(findings("unannotated-parameter.java"), "undeclared-parameter", "text");
        assertRule(findings("unannotated-return.java"), "undeclared-return", "echo");
    }

    @Test
    @DisplayName("a nullable parameter and a nullable return are refused distinctly")
    void declaredAbsenceIsRefused() {
        assertRule(findings("nullable-parameter.java"), "nullable-parameter", "text");
        assertRule(findings("nullable-return.java"), "nullable-return", "echo");
    }

    @Test
    @DisplayName("a wrapper is permitted as a return and refused as a parameter and as a field")
    void theWrapperIsPermittedInOnePositionOnly() {
        assertRule(findings("optional-parameter.java"), "optional-parameter", "text");
        assertRule(findings("optional-field.java"), "optional-field", "text");
        assertEquals(List.of(), findings("accepted.java"),
                "the accepted file, which returns a wrapper, was refused");
    }

    @Test
    @DisplayName("a null value returned and a null value passed are refused distinctly")
    void nullValuesAreRefused() {
        assertRule(findings("null-returned.java"), "null-return", "return null");
    }

    @Test
    @DisplayName("a null value reaching an argument through a variable is caught")
    void aNullReachingAnArgumentThroughAVariableIsCaught() {
        final List<PolicyFinding> findings = findings("null-passed.java");
        assertRule(findings, "null-argument", "absent");
        assertTrue(findings.stream().noneMatch(finding -> "null-return".equals(finding.rule())),
                "the fixture returns no null value and one was reported: " + findings);
    }

    @Test
    @DisplayName("annotating a primitive states what the language already decides and is refused")
    void aRedundantAnnotationIsRefused() {
        assertRule(findings("redundant-annotation.java"), "redundant-annotation", "count");
    }

    @Test
    @DisplayName("the accepted file exercises every permitted form and passes")
    void theAcceptedFilePasses() {
        assertEquals(List.of(), findings("accepted.java"));
    }

    @Test
    @DisplayName("the policy names both permitted forms and every exemption with a reason")
    void thePolicyNamesItsFormsAndExemptions() {
        assertEquals(List.of("member-annotation", "package-default"), policy().permittedForms());
        assertEquals(List.of("primitive", "private-member", "test-source"), policy().exemptKinds());
    }

    @Test
    @DisplayName("neither bundle imports the annotation package, read from its manifest")
    void neitherBundleImportsTheAnnotationPackage() {
        final String version = ReactorModel.at(REPOSITORY).aggregator().getVersion();
        List.of("core", "aem").forEach(module -> {
            final BuiltArtifact bundle = BuiltArtifact.at(REPOSITORY.resolve(module).resolve("target")
                    .resolve("slingshot-agent-" + module + "-" + version + ".jar"));
            assertEquals(List.of(), ImportedPackages.importsUnder(bundle, ANNOTATION_PACKAGE),
                    module + " imports the annotation package, which no instance carries at runtime");
        });
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static NullabilityPolicy policy() {
        return assertInstanceOf(NullabilityPolicy.Loaded.class, NullabilityPolicy.read(REPOSITORY),
                "the nullability policy was refused").policy();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }
}
