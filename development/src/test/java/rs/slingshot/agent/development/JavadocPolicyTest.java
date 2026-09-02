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
 * The half of documentation a checker can decide, proved against eight ways of failing it.
 *
 * <p>The rule worth having is the name-restating summary. Documentation that spells the member's
 * own name back is present, passes every count, and says nothing — and it is exactly what a
 * generator produces when somebody is filling a gate rather than explaining a contract.</p>
 */
final class JavadocPolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/javadoc");

    @Test
    @DisplayName("every documented member in this repository is documented completely")
    void thisRepositoryIsDocumentedCompletely() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("an undocumented type and an undocumented parameter are refused distinctly")
    void absentDocumentationIsRefused() {
        assertRule(findings("undocumented-type.java"), "documentation-absent", "UndocumentedType");
        assertRule(findings("undocumented-parameter.java"), "documentation-parameter", "second");
    }

    @Test
    @DisplayName("an undescribed return and an undescribed failure are refused distinctly")
    void undescribedContractsAreRefused() {
        assertRule(findings("undescribed-return.java"), "documentation-return",
                "join does not describe what it returns");
        assertRule(findings("undescribed-failure.java"), "documentation-failure", "IOException");
    }

    @Test
    @DisplayName("an undescribed type parameter is refused")
    void anUndescribedTypeParameterIsRefused() {
        assertRule(findings("undocumented-type-parameter.java"), "documentation-type-parameter", "T");
    }

    @Test
    @DisplayName("a summary that restates the member name and a placeholder are refused distinctly")
    void emptyDocumentationIsRefused() {
        assertRule(findings("summary-restates-the-name.java"), "documentation-restates-name",
                "joinedText");
        assertRule(findings("placeholder-summary.java"), "documentation-placeholder", "echo");
    }

    @Test
    @DisplayName("the name-restating rule refuses a corpus of shapes and accepts a real summary")
    void theNameRestatingRuleIsDecidedAcrossShapes() {
        assertTrue(JavadocPolicy.restatesName("The joined text.", "joinedText"));
        assertTrue(JavadocPolicy.restatesName("Joined text", "joinedText"));
        assertTrue(JavadocPolicy.restatesName("Returns the joined text.", "joinedText"));
        assertTrue(JavadocPolicy.restatesName("A policy report.", "PolicyReport"));
        assertFalse(JavadocPolicy.restatesName("Joins two pieces of text with nothing between them.",
                "joinedText"));
        assertFalse(JavadocPolicy.restatesName("Everything one or more checks found, in one order.",
                "PolicyReport"));
    }

    @Test
    @DisplayName("the accepted fixture passes every completeness rule")
    void theAcceptedFixturePasses() {
        assertEquals(List.of(), findings("accepted.java"));
    }

    @Test
    @DisplayName("a package with no package documentation is refused naming it")
    void aPackageWithNoDocumentationIsRefused() {
        assertRule(policy().across(FIXTURES.resolve("nopackage")).findings(),
                "documentation-package", "rs.slingshot.agent.fixture.undocumented");
    }

    @Test
    @DisplayName("inherited documentation is permitted where the contract is unchanged")
    void inheritedDocumentationIsPermittedOnUnchangedContracts() {
        final Path overriding = REPOSITORY.resolve(
                "development/src/main/java/rs/slingshot/agent/development/PolicyReport.java");
        assertEquals(List.of(), policy().inFile("PolicyReport.java", overriding));
    }

    @Test
    @DisplayName("the policy records what a reader decides rather than pretending to decide it")
    void thePolicyRecordsWhatItDoesNotDecide() {
        assertTrue(policy().reviewQuestions().size() >= 5,
                "the policy records fewer review questions than it has undecidable subjects");
        assertEquals(List.of("restates-the-member-name", "empty-or-placeholder"),
                policy().refusedSummaries());
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static JavadocPolicy policy() {
        return assertInstanceOf(JavadocPolicy.Loaded.class, JavadocPolicy.read(REPOSITORY),
                "the documentation policy was refused").policy();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }
}
