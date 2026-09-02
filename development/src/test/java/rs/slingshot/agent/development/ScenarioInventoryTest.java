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
 * Whether every feature this repository serves brings its own interoperability test.
 *
 * <p>The comparison is vacuous today and that is the point: it is in place before the registry has
 * rows in it, so the day a route starts being served is the day the gate asks what proves it. Every
 * rejection here is proved on a fixture rather than by reading the committed tree, because a check
 * that only ever sees a passing repository is one nobody has watched refuse anything.</p>
 */
final class ScenarioInventoryTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/scenario-inventory");

    @Test
    @DisplayName("every scenario this repository declares names a feature, a tier, and a class")
    void theCommittedInventoryHolds() {
        assertEquals("", inventory().against(sources()).render());
        assertTrue(inventory().scenarios().size() >= 2,
                "the committed inventory lost a scenario");
    }

    @Test
    @DisplayName("the committed scenarios cover every route the product actually serves")
    void everyServedRouteIsCovered() {
        final List<String> served = ScenarioInventory.servedFeatures(sources());
        assertEquals(List.of("capabilities", "submit", "operation-lookup", "physical-job-lookup",
                        "subscription-high-water", "events", "artifact-transfer",
                        "artifact-intake"), served,
                "the product serves a route this commit does not claim to: " + served);
    }

    @Test
    @DisplayName("an unknown feature, tier, deployment, kind, and property are refused distinctly")
    void thefiveWaysOfNamingSomethingThatIsNotThereAreRefused() {
        assertRefusal("unknown-feature", "unknown-feature", "teleportation is not a route");
        assertRefusal("unknown-tier", "unknown-tier", "z is not a tier");
        assertRefusal("unknown-deployment", "unknown-deployment", "aem-7-0");
        assertRefusal("unknown-kind", "unknown-kind", "sideways is not a route, a property, or a command");
        assertRefusal("unknown-property", "unknown-property", "unheld-policy names no committed");
    }

    @Test
    @DisplayName("a scenario declared twice and a scenario with no class are refused distinctly")
    void aDuplicateAndAnAbsentClassAreRefused() {
        assertRefusal("duplicate-scenario", "duplicate-scenario",
                "walking-skeleton is declared more than once");
        assertRefusal("scenario-with-no-class", "scenario-with-no-class",
                "rs.slingshot.agent.interop.tier.AbsentScenario does not exist");
    }

    @Test
    @DisplayName("a runner class no scenario declares is refused naming the class")
    void aClassWithNoRowIsRefused() {
        final PolicyReport report = inventoryAt("accepted").against(sources()
                .withScenarios(FIXTURES.resolve("accepted/scenarios"))
                .withRunners(FIXTURES.resolve("class-with-no-scenario/runners")));
        assertRule(report, "class-with-no-scenario", "StrayScenario.java runs no declared scenario");
    }

    @Test
    @DisplayName("a feature that starts being served fails until it brings a scenario")
    void addingAFeatureFailsUntilItBringsAScenario() {
        final ScenarioInventory.Sources added = sources()
                .withScenarios(FIXTURES.resolve("accepted/scenarios"))
                .withFeatures(FIXTURES.resolve("added-feature/agent-routes.toml"),
                        FIXTURES.resolve("added-feature/registrations"));
        assertEquals(List.of("capabilities", "teleportation"),
                ScenarioInventory.servedFeatures(added),
                "the served features were not read from the inventory the fixture declares");
        assertRule(inventoryAt("accepted").against(added), "uncovered-feature",
                "teleportation is served and no scenario covers it; it needs one on tier a");
    }

    @Test
    @DisplayName("a scenario file that does not satisfy its own shape refuses the whole inventory")
    void aScenarioFileOutsideItsShapeIsRefused() {
        final ScenarioInventory.Outcome outcome =
                ScenarioInventory.read(REPOSITORY.resolve("policy"));
        assertInstanceOf(ScenarioInventory.Refused.class, outcome,
                "a directory of documents that are not scenarios produced an inventory");
    }

    private void assertRefusal(String fixture, String rule, String named) {
        assertRule(inventoryAt(fixture).against(sources()
                        .withScenarios(FIXTURES.resolve(fixture + "/scenarios"))),
                rule, named);
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.rule().equals(rule)
                                && finding.symbol().contains(named)),
                "no " + rule + " finding named " + named + ": " + report.render());
    }

    private static ScenarioInventory inventory() {
        return loaded(ScenarioInventory.at(REPOSITORY));
    }

    private static ScenarioInventory inventoryAt(String fixture) {
        return loaded(ScenarioInventory.read(FIXTURES.resolve(fixture + "/scenarios")));
    }

    private static ScenarioInventory loaded(ScenarioInventory.Outcome outcome) {
        return assertInstanceOf(ScenarioInventory.Loaded.class, outcome,
                "the inventory was refused: " + ScenarioInventory.refusal(outcome).orElse(""))
                .inventory();
    }

    private static ScenarioInventory.Sources sources() {
        return ScenarioInventory.Sources.of(REPOSITORY);
    }
}
