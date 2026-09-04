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
 * The floor a module and a class have to reach, proved at the floor and one step below it.
 *
 * <p>The two measures are checked independently on purpose: a fixture that meets the line floor and
 * misses the branch floor is refused, because a branch nobody took is a decision nobody proved even
 * where every line ran.</p>
 */
final class CoverageFloorTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/coverage-floor");

    private static final String CLASS_UNDER_MEASURE = "rs.slingshot.agent.contract.AgentContract";

    @Test
    @DisplayName("both minimums are declared once, and the build holds exactly them")
    void bothMinimumsAreDeclaredOnce() {
        assertEquals(80L, policy().floor("line"));
        assertEquals(70L, policy().floor("branch"));
        assertEquals("", policy().againstTheBuild(ReactorModel.at(REPOSITORY)).render());
    }

    @Test
    @DisplayName("a policy the build does not hold is refused naming both values")
    void aPolicyTheBuildDoesNotHoldIsRefused() {
        assertRule(policyAt("floor-the-build-does-not-hold.toml")
                        .againstTheBuild(ReactorModel.at(REPOSITORY)),
                "coverage-floor", "0.80 in the build and 0.90 in the policy");
    }

    @Test
    @DisplayName("a class at exactly the floor passes and one step below fails, naming everything")
    void theFloorHoldsAtBothSides() {
        assertEquals("", policy().shortfalls(List.of(
                new CoverageFloor.Measurement("core", CLASS_UNDER_MEASURE, "line", 80),
                new CoverageFloor.Measurement("core", CLASS_UNDER_MEASURE, "branch", 70))).render());
        final PolicyReport below = policy().shortfalls(List.of(
                new CoverageFloor.Measurement("core", CLASS_UNDER_MEASURE, "line", 79)));
        assertRule(below, "coverage-floor", "covers 79 per cent of its lines, below the floor of 80");
        assertEquals("core", below.findings().getFirst().file());
    }

    @Test
    @DisplayName("the two measures are enforced independently")
    void theTwoMeasuresAreEnforcedIndependently() {
        final PolicyReport report = policy().shortfalls(List.of(
                new CoverageFloor.Measurement("core", CLASS_UNDER_MEASURE, "line", 95),
                new CoverageFloor.Measurement("core", CLASS_UNDER_MEASURE, "branch", 69)));
        assertEquals(1, report.findings().size(), report.render());
        assertRule(report, "coverage-floor", "branch");
    }

    @Test
    @DisplayName("an exclusion with no reason and a package-level exclusion are refused distinctly")
    void bothWaysOfExcludingInBulkAreRefused() {
        final CoverageFloor.Outcome unexplained =
                CoverageFloor.readPolicy(FIXTURES.resolve("exclusion-with-no-reason.toml"));
        assertTrue(assertInstanceOf(CoverageFloor.Refused.class, unexplained,
                "an exclusion with no reason was accepted").detail().contains("records no reason"));
        final CoverageFloor.Outcome wholesale =
                CoverageFloor.readPolicy(FIXTURES.resolve("package-level-exclusion.toml"));
        assertTrue(assertInstanceOf(CoverageFloor.Refused.class, wholesale,
                "a package-level exclusion was accepted").detail().contains("excludes a package"));
    }

    @Test
    @DisplayName("an excluded class is not measured, and the exclusion carries its reason")
    void anExcludedClassIsNotMeasured() {
        final CoverageFloor excluding = policyAt("accepted-exclusion.toml");
        assertEquals("", excluding.shortfalls(List.of(new CoverageFloor.Measurement("core",
                "rs.slingshot.agent.contract.ContractLimit", "line", 0))).render());
        assertEquals(1, excluding.exclusions().size());
        assertFalse(excluding.exclusions().getFirst().reason().isBlank());
    }

    @Test
    @DisplayName("every exclusion names one class of a tier this gate does not run, with its reason")
    void everyExclusionIsATierTheGateDoesNotRun() {
        assertFalse(policy().exclusions().isEmpty(),
                "the policy excludes nothing, and this proves nothing about what it excludes");
        policy().exclusions().forEach(excluded -> {
            assertFalse(excluded.reason().isBlank(),
                    excluded.className() + " is excluded and records no reason");
            assertTrue(excluded.className().startsWith("rs.slingshot.agent.interop."),
                    excluded.className() + " is excluded and is not harness code. Only a class of a"
                            + " tier this gate does not run may be, because that is the one kind"
                            + " whose coverage this gate cannot produce.");
        });
    }

    @Test
    @DisplayName("the measured modules are the product and the harness, and each exists")
    void theMeasuredModulesAreTheProductAndTheHarness() {
        assertEquals(List.of("core", "aem", "interop"), policy().measuredModules());
        assertEquals("", policy().againstTheBuild(ReactorModel.at(REPOSITORY)).render());
    }

    private static CoverageFloor policy() {
        return assertInstanceOf(CoverageFloor.Loaded.class, CoverageFloor.read(REPOSITORY),
                "the coverage policy was refused").policy();
    }

    private static CoverageFloor policyAt(String fixture) {
        return assertInstanceOf(CoverageFloor.Loaded.class,
                CoverageFloor.readPolicy(FIXTURES.resolve(fixture)),
                fixture + " was refused").policy();
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + report.render());
    }
}
