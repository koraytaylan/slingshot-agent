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
 * The analysers, and the two claims that make them worth having.
 *
 * <p>That every category a scan would raise is already decided at build time, so a scan has nothing
 * left to say. And that no rule can be switched off: the exclusion filter is empty and stays empty,
 * every rule the source-pattern set does not run is a recorded decision with a reason, and every
 * suppression form is refused in source wherever it appears.</p>
 */
final class StaticAnalysisConfigurationTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/static-analysis");

    /** The rule set whose exclusions are recorded decisions. */
    private static final String PMD_RULE_SET = "policy/analysis/pmd.xml";

    @Test
    @DisplayName("the three declared analysers are the three the build runs")
    void theDeclaredAnalysersAreTheOnesTheBuildRuns() {
        assertEquals(List.of("checkstyle", "pmd", "spotbugs"),
                policy().analysers().stream()
                        .map(StaticAnalysis.AnalyserRow::identifier)
                        .toList());
        assertEquals("", policy().configuration(ReactorModel.at(REPOSITORY)).render());
    }

    @Test
    @DisplayName("every analyser fails at its first finding and reads test sources")
    void everyAnalyserFailsAndReadsTests() {
        policy().analysers().forEach(analyser -> {
            assertTrue(analyser.failsAtFirstFinding(),
                    analyser.identifier() + " produces a report rather than failing the build");
            assertTrue(analyser.coversTestSources(),
                    analyser.identifier() + " does not read test sources");
        });
    }

    @Test
    @DisplayName("every category a scan would raise has a covering analyser here")
    void everyScanCategoryIsCovered() {
        assertEquals(List.of("bug", "vulnerability", "security-hotspot", "maintainability", "convention"),
                policy().categories().stream()
                        .map(StaticAnalysis.CategoryRow::name)
                        .toList());
        assertEquals("", policy().categoryCoverage().render());
        policy().categories().forEach(category -> assertTrue(!category.reason().isBlank(),
                category.name() + " records no reason"));
    }

    @Test
    @DisplayName("a category naming no analyser, and an analyser covering nothing, are both refused")
    void categoryCoverageIsCheckedInBothDirections() {
        assertNames(policyAt("category-with-no-analyser.toml").categoryCoverage(),
                "nothing-runs-this");
        assertNames(policyAt("analyser-covering-nothing.toml").categoryCoverage(),
                "an-analyser-no-category-names");
    }

    @Test
    @DisplayName("the rules that overlap this repository's own doctrine are enabled deliberately")
    void overlappingRulesAreDeclared() {
        final List<String> declared = policy().overlappingRules().stream()
                .map(StaticAnalysis.OverlappingRule::rule)
                .toList();
        assertTrue(declared.contains("NP_NULL_ON_SOME_PATH"), declared.toString());
        assertTrue(declared.contains("UnusedPrivateField"), declared.toString());
        assertTrue(declared.contains("OBL_UNSATISFIED_OBLIGATION"), declared.toString());
        assertTrue(declared.contains("EQ_COMPARETO_USE_OBJECT_EQUALS"), declared.toString());
        policy().overlappingRules().forEach(rule -> assertTrue(!rule.doctrine().isBlank(),
                rule.rule() + " names no doctrine it is a second opinion on"));
    }

    @Test
    @DisplayName("the bug-pattern filter is empty, and a filter that carries anything is refused")
    void theExclusionFilterIsEmpty() {
        assertEquals("", policy().exclusionFilter(REPOSITORY).render());
        assertEquals("", policy().exclusionFilterAt(FIXTURES.resolve("empty-filter.xml"),
                "empty-filter.xml").render());
        assertNames(policy().exclusionFilterAt(FIXTURES.resolve("non-empty-filter.xml"),
                "non-empty-filter.xml"), "a filtered finding");
    }

    @Test
    @DisplayName("every rule the source-pattern set does not run is a recorded decision")
    void everyRuleSetExclusionIsRecorded() {
        assertEquals("", policy().ruleSetExclusions(REPOSITORY, PMD_RULE_SET).render());
        assertNames(policy().ruleSetExclusions(FIXTURES,
                "rule-set-with-unrecorded-exclusion.xml"), "NobodyRecordedThis");
    }

    @Test
    @DisplayName("no repository-owned Java switches a rule off")
    void nothingIsSwitchedOffInSource() {
        assertEquals("", policy().suppressions(REPOSITORY).render());
    }

    @Test
    @DisplayName("an annotation, an analyser-specific annotation, and a comment are each refused")
    void everySuppressionFormIsRefused() {
        assertNames(refusals("suppression-annotation.java"), "@SuppressWarnings");
        assertNames(refusals("analyser-specific-suppression.java"), "@SuppressFBWarnings");
        assertNames(refusals("suppression-comment.java"), "CHECKSTYLE:OFF");
        assertNames(refusals("suppression-comment.java"), "NOPMD");
        assertNames(refusals("suppression-comment.java"), "NOSONAR");
    }

    @Test
    @DisplayName("a file naming every suppression form only as text carries none of them")
    void namingAFormIsNotUsingIt() {
        assertEquals(List.of(), policy().suppressionsIn("accepted.java",
                FIXTURES.resolve("accepted.java")));
    }

    private static PolicyReport refusals(String fixture) {
        return PolicyReport.of(policy().suppressionsIn(fixture, FIXTURES.resolve(fixture)));
    }

    private static StaticAnalysis policy() {
        return assertInstanceOf(StaticAnalysis.Loaded.class, StaticAnalysis.read(REPOSITORY),
                "the static-analysis policy was refused").policy();
    }

    private static StaticAnalysis policyAt(String fixture) {
        return assertInstanceOf(StaticAnalysis.Loaded.class,
                StaticAnalysis.readPolicy(FIXTURES.resolve(fixture)),
                fixture + " was refused").policy();
    }

    private static void assertNames(PolicyReport report, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream().anyMatch(finding -> finding.symbol().contains(named)),
                "no finding names " + named + ": " + report.render());
    }
}
