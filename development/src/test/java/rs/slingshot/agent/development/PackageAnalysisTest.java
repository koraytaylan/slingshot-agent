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
 * The analysers the container and every content package are held to.
 *
 * <p>The task set is checked in both directions against a transcript of the analyser actually
 * running. A task it runs and nobody declared is a check nobody chose; a task somebody declared and
 * it does not run is a claim about a check that is not happening. Either one on its own would let
 * the policy drift away from the build it describes.</p>
 */
final class PackageAnalysisTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/package-analysis");

    @Test
    @DisplayName("the analyser and the validators are bound the way the policy declares")
    void theAnalysersAreBoundAsDeclared() {
        assertEquals("", policy().binding(ReactorModel.at(REPOSITORY)).render());
    }

    @Test
    @DisplayName("the analyser ran exactly the task set the policy declares")
    void theTaskSetIsExactlyTheDeclaredOne() {
        assertEquals("", policy().taskSet(transcript("analyser-transcript.txt")).render());
        assertTrue(policy().tasks().size() >= 17,
                "the policy declares fewer tasks than the analyser runs");
    }

    @Test
    @DisplayName("a task run and not declared, and one declared and not run, are refused distinctly")
    void theTaskSetIsCheckedInBothDirections() {
        assertRule(policy().taskSet(transcript("transcript-with-an-undeclared-task.txt")),
                "analyser-task", "nobody-declared-this ran and the policy names no such task");
        assertRule(policy().taskSet(transcript("transcript-missing-a-declared-task.txt")),
                "analyser-task", "repoinit is declared and the analyser did not run it");
    }

    @Test
    @DisplayName("every declared task and validator records what it decides")
    void everyTaskRecordsWhatItDecides() {
        policy().tasks().forEach(task -> assertTrue(!task.reason().isBlank(),
                task.name() + " is run and records no reason"));
        policy().validators().forEach(validator -> assertTrue(!validator.reason().isBlank(),
                validator.name() + " is run and records no reason"));
        assertEquals(List.of("jackrabbit-filter", "jackrabbit-packagetype", "jackrabbit-nodetypes",
                        "jackrabbit-accesscontrol", "jackrabbit-dependencies"),
                policy().validators().stream()
                        .map(PackageAnalysis.TaskRow::name)
                        .toList());
    }

    @Test
    @DisplayName("an analyser that reports rather than fails refuses the whole policy")
    void anAnalyserThatReportsRefusesThePolicy() {
        assertInstanceOf(PackageAnalysis.Refused.class,
                PackageAnalysis.readPolicy(FIXTURES.resolve("analyser-that-reports.toml")),
                "a policy letting the analyser report rather than fail was accepted");
        assertInstanceOf(PackageAnalysis.Refused.class,
                PackageAnalysis.readPolicy(FIXTURES.resolve("task-with-no-reason.toml")),
                "a task with no reason was accepted");
    }

    @Test
    @DisplayName("the container embeds exactly what the policy declares, in the declared order")
    void theContainerEmbedsExactlyWhatIsDeclared() {
        assertEquals("", policy().containerContents(ReactorModel.at(REPOSITORY)).render());
        assertEquals(policy().embedded().size(),
                policy().builtEmbeds(ReactorModel.at(REPOSITORY)).size(),
                "the container's contents and the policy's rows are different sizes");
    }

    @Test
    @DisplayName("a declared embed the container does not carry is refused naming it")
    void aDeclaredEmbedTheContainerLacksIsRefused() {
        assertRule(policyAt("embed-with-no-build.toml").containerContents(ReactorModel.at(REPOSITORY)),
                "container-contents",
                "slingshot-agent-nothing has a row and the container embeds no such artifact");
    }

    private static String transcript(String fixture) {
        return RepositoryTree.text(FIXTURES.resolve(fixture));
    }

    private static PackageAnalysis policy() {
        return assertInstanceOf(PackageAnalysis.Loaded.class, PackageAnalysis.read(REPOSITORY),
                "the analysis policy was refused").policy();
    }

    private static PackageAnalysis policyAt(String fixture) {
        return assertInstanceOf(PackageAnalysis.Loaded.class,
                PackageAnalysis.readPolicy(FIXTURES.resolve(fixture)),
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
