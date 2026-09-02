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
 * The shape a method may have, proved at each ceiling and one step past it.
 *
 * <p>The test that matters most is the pair: a method inside the complexity ceiling and past the
 * nesting one is refused. If nesting were inferred from complexity rather than measured, that
 * method would pass — and it is exactly the shape this policy exists to refuse.</p>
 */
final class MethodShapePolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/method-shape");

    @Test
    @DisplayName("every main-source method in this repository holds every ceiling")
    void thisRepositoryHoldsEveryCeiling() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("every ceiling is read from the policy and declared nowhere in the checker")
    void everyCeilingIsReadFromThePolicy() {
        assertEquals(List.of("nesting-depth", "cyclomatic-complexity", "cognitive-complexity",
                        "method-length", "parameter-count"),
                List.copyOf(policy().ceilings().keySet()));
        assertEquals(3L, policy().ceiling("nesting-depth"));
        assertEquals(10L, policy().ceiling("cyclomatic-complexity"));
        final String checker = RepositoryTree.text(REPOSITORY.resolve(
                "development/src/main/java/rs/slingshot/agent/development/MethodShapePolicy.java"));
        assertTrue(!checker.contains("= 3;") && !checker.contains("= 10;"),
                "a ceiling is written down in the checker as well as in the policy");
    }

    @Test
    @DisplayName("nesting holds at exactly the ceiling and refuses one level past it")
    void nestingHoldsAtBothSidesOfTheCeiling() {
        assertEquals(List.of(), findings("at-the-nesting-ceiling.java"));
        assertRule(findings("one-past-the-nesting-ceiling.java"), "nesting-depth", "nests 4 deep");
    }

    @Test
    @DisplayName("a method inside the complexity ceiling and past the nesting one is refused")
    void nestingIsEnforcedIndependentlyOfComplexity() {
        final List<PolicyFinding> findings = findings("nesting-past-ceiling-complexity-inside.java");
        assertRule(findings, "nesting-depth", "found nests 4 deep");
        assertTrue(findings.stream()
                        .noneMatch(finding -> "cyclomatic-complexity".equals(finding.rule())),
                "the method is inside the complexity ceiling and was reported for it: " + findings);
    }

    @Test
    @DisplayName("an else after an exhaustively returning block is refused and its rewrite passes")
    void theReturningBlockElseIsRefusedAndItsRewriteIsNot() {
        assertRule(findings("returning-block-else.java"), "returning-block-else",
                "returns on every path");
        assertEquals(List.of(), findings("guarded-rewrite.java"));
    }

    @Test
    @DisplayName("every nesting finding names the guard clause that would remove it")
    void everyNestingFindingNamesItsGuardClause() {
        findings("one-past-the-nesting-ceiling.java").stream()
                .filter(finding -> "nesting-depth".equals(finding.rule()))
                .forEach(finding -> assertTrue(finding.symbol().contains("guard clause"),
                        "the finding says what is wrong and not what to do: " + finding));
    }

    @Test
    @DisplayName("a two-valued choice is refused and a reported fact is not")
    void aChoiceIsRefusedAndAFactIsNot() {
        assertRule(findings("boolean-choice.java"), "boolean-choice", "refuse is a choice");
        assertEquals(List.of(), findings("reported-fact.java"),
                "a fact the code observed was refused as though it were a choice");
    }

    @Test
    @DisplayName("the policy names every position in which a two-valued value is a choice")
    void thePolicyNamesEveryChoicePosition() {
        assertEquals(List.of("method-parameter", "command-argument", "configuration-value"),
                policy().choicePositions());
    }

    @Test
    @DisplayName("a method with more arguments than the ceiling allows is refused")
    void theParameterCeilingIsRefused() {
        assertRule(findings("too-many-parameters.java"), "parameter-count", "takes 8 arguments");
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static MethodShapePolicy policy() {
        return assertInstanceOf(MethodShapePolicy.Loaded.class, MethodShapePolicy.read(REPOSITORY),
                "the method-shape policy was refused").policy();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }
}
