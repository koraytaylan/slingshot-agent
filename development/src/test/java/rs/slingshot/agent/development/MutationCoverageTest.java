// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether every command that changes something is proved by exactly one cross-cutting suite.
 *
 * <p>Each rejection is proved on a directory with exactly one thing wrong with it, so a failure
 * names the thing rather than the directory. The committed registry is checked whole in the first
 * assertion, which is what makes the others mean something: a check that only ever saw broken input
 * would pass on a check that refused everything.</p>
 */
final class MutationCoverageTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/mutation-coverage");

    @Test
    @DisplayName("the committed registry partitions cleanly across the cross-cutting suites")
    void thecommittedRegistryPartitionsCleanly() {
        assertEquals("", MutationCoverage.against(MutationCoverage.Sources.of(REPOSITORY))
                .render());
        assertTrue(MutationCoverage.declaredOutcomes(
                        REPOSITORY.resolve(MutationCoverage.REGISTRY_DIRECTORY), categories())
                        .values().stream().anyMatch(declared -> !declared.isEmpty()),
                "no committed row declares what kind of change it makes, so this check is passing"
                        + " over an empty registry rather than proving one");
    }

    @Test
    @DisplayName("the categories are read from the committed policy rather than written in Java")
    void thecategoriesComeFromThePolicy() {
        assertTrue(categories().contains("mutation_outcome_unknown")
                        && categories().contains("admission_outcome_unknown")
                        && categories().contains("platform_control_outcome_unknown"),
                "the committed policy no longer declares the three kinds of change, and the plan"
                        + " that adds a fourth is supposed to need no edit to this check: "
                        + categories());
        assertEquals(List.of(), MutationCoverage.outcomeCategories(FIXTURES.resolve("nothing.toml")),
                "a policy that is not there was read as declaring categories");
    }

    @Test
    @DisplayName("a row claimed by nobody and one claimed by two suites are distinct findings")
    void unclaimedAndDoublyClaimedAreDistinct() {
        assertRule(against("unclaimed"), MutationCoverage.UNCLAIMED,
                "no cross-cutting scenario claims it");
        assertRule(against("doubly-claimed"), MutationCoverage.DOUBLY_CLAIMED,
                "each claim it");
    }

    @Test
    @DisplayName("a row declaring two kinds of change is a command that has not decided what it is")
    void arowDeclaringTwoKindsIsRefused() {
        assertRule(against("several-outcomes"), MutationCoverage.SEVERAL_OUTCOMES,
                "is two commands");
    }

    @Test
    @DisplayName("a suite claiming a category the policy does not declare is refused")
    void asuiteClaimingSomethingElseIsRefused() {
        assertRule(against("unknown-claim"), MutationCoverage.UNKNOWN_CLAIM,
                "which is not one of");
    }

    @Test
    @DisplayName("what each suite claims is read from the scenario files rather than from here")
    void theclaimsAreReadFromTheScenarios() {
        assertTrue(MutationCoverage.claims(REPOSITORY.resolve(MutationCoverage.SCENARIO_DIRECTORY))
                        .containsKey("mutation_outcome_unknown"),
                "no committed scenario claims the repository mutation's own category, so every"
                        + " command that writes is proved by nothing across all of them");
        assertEquals(Map.of(),
                MutationCoverage.claims(FIXTURES.resolve("nowhere")),
                "scenarios that are not there were read as claiming something");
    }

    private static String against(String fixture) {
        return MutationCoverage.against(MutationCoverage.Sources.of(REPOSITORY)
                        .withRegistry(FIXTURES.resolve(fixture).resolve("commands"))
                        .withScenarios(FIXTURES.resolve(fixture).resolve("scenarios")))
                .render();
    }

    private static List<String> categories() {
        return MutationCoverage.outcomeCategories(
                REPOSITORY.resolve(MutationCoverage.POLICY_FILE));
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
