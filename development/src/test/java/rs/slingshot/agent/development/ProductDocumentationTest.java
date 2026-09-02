// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the product's documents claim, checked against the tables that decide it.
 *
 * <p>Every refusal here is proved on a fixture document rather than by damaging the committed ones,
 * and the fixture root borrows this repository's own policy directory so that a rule naming a stage
 * is judged against the stages the gate actually declares rather than against a copy of them.</p>
 */
final class ProductDocumentationTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/product-documentation");

    @Test
    @DisplayName("the committed documents name only routes, tiers, stages, and policies that exist")
    void theCommittedDocumentsHold() {
        assertEquals("", documentation().against(REPOSITORY).render());
    }

    @Test
    @DisplayName("the rules file names exactly the rules the checker decides, and no question does")
    void theRulesFileAndTheCheckerAgree() {
        assertEquals("", documentation().rulesAgree().render());
        assertFalse(documentation().review().isEmpty(),
                "the checklist lost every question a checker cannot answer");
    }

    @Test
    @DisplayName("a marker for work not done and a heading for work to come are both refused")
    void aMarkerAndAPlanningHeadingAreRefused(@TempDir Path root) {
        final PolicyReport report = documentation().against(rootWith(root, "README.md"));
        assertRule(report, "unfinished-work", "TODO");
        assertRule(report, "planning-heading", "## Roadmap");
    }

    @Test
    @DisplayName("a route named in prose that no table declares is refused naming the route")
    void aRouteNoTableDeclaresIsRefused(@TempDir Path root) {
        assertRule(documentation().against(rootWith(root, "README.md")), "unknown-route",
                "/bin/slingshot/agent/teleportation");
    }

    @Test
    @DisplayName("a rule with no stage and a rule naming no declared stage are refused distinctly")
    void bothWaysOfStatingAnUnenforcedRuleAreRefused(@TempDir Path root) {
        final PolicyReport report = documentation().against(rootWith(root, "CONTRIBUTING.md"));
        assertRule(report, "rule-with-no-stage", "nothing enforces it");
        assertRule(report, "unknown-stage", "teleportation is not a stage");
    }

    @Test
    @DisplayName("a policy described and absent, and one that exists and is undescribed, both fail")
    void bothDirectionsOfThePolicyComparisonAreChecked(@TempDir Path root) {
        final PolicyReport report = documentation().against(rootWith(root, "CONTRIBUTING.md"));
        assertRule(report, "unknown-policy", "policy/teleportation.toml");
        assertRule(report, "undocumented-policy", "policy/nullability.toml");
    }

    @Test
    @DisplayName("a route the product serves and no document names is refused naming the route")
    void aServedRouteNobodyDocumentedIsRefused(@TempDir Path root) {
        assertRule(documentation().against(rootWith(root, "CONTRIBUTING.md")),
                "undocumented-route", "/bin/slingshot/agent/capabilities");
    }

    @Test
    @DisplayName("every declared tier command is named, and no command that runs no tier is")
    void theTierCommandsAreExactlyTheDeclaredOnes() {
        final String described = read(REPOSITORY.resolve("docs/INTEROP.md"));
        final QualityGate gate = assertInstanceOf(QualityGate.Loaded.class,
                QualityGate.read(REPOSITORY), "the gate inventory was refused").gate();
        gate.tiers().forEach(tier -> assertTrue(described.contains(tier.command()),
                tier.command() + " runs a tier and the interoperability document does not name it"));
        assertEquals(List.of("a", "b", "c"), gate.tiers().stream()
                .map(QualityGate.TierRow::name)
                .toList());
    }

    @Test
    @DisplayName("a checklist question restating a checked rule is refused, and so is a rule"
            + " nothing decides")
    void aChecklistThatRepeatsTheCheckerIsRefused() {
        assertRule(rulesAt("restated-question.toml").rulesAgree(), "unknown-rule",
                "unfinished-work is reviewed and the checker already decides it");
        assertRule(rulesAt("rule-nothing-decides.toml").rulesAgree(), "unknown-rule",
                "sunshine is named as checked and no checker decides it");
    }

    @Test
    @DisplayName("the review answers every question the checklist holds")
    void theReviewIsFinished() {
        documentation().review().forEach(question ->
                assertTrue(read(REPOSITORY.resolve("docs/DOCUMENTATION_REVIEW.md"))
                                .contains("## " + question.identifier()),
                        question.identifier() + " is on the checklist and unanswered"));
    }

    /**
     * A root holding one fixture document and this repository's own policy directory.
     *
     * <p>The policies are borrowed rather than copied so that a fixture rule is judged against the
     * stages the gate declares today. A copy would pass on the day it was made and refuse something
     * else a month later, which is the failure mode the fixture exists to catch.</p>
     */
    private static Path rootWith(Path root, String document) {
        try {
            Files.copy(FIXTURES.resolve(document), root.resolve(document));
            Files.createSymbolicLink(root.resolve("policy"), REPOSITORY.resolve("policy"));
            Files.createSymbolicLink(root.resolve("core"), REPOSITORY.resolve("core"));
            return root;
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.rule().equals(rule)
                                && finding.symbol().contains(named)),
                "no " + rule + " finding named " + named + ": " + report.render());
    }

    private static ProductDocumentation documentation() {
        return assertInstanceOf(ProductDocumentation.Loaded.class,
                ProductDocumentation.read(REPOSITORY), "the rules file was refused").rules();
    }

    private static ProductDocumentation rulesAt(String fixture) {
        return assertInstanceOf(ProductDocumentation.Loaded.class,
                ProductDocumentation.readRules(FIXTURES.resolve(fixture)),
                "the fixture rules file was refused").rules();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
