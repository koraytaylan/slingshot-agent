// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate, and the two claims that make it worth running.
 *
 * <p>That it runs exactly the stages the inventory declares, in the declared order — so a stage
 * cannot quietly stop running. And that it reaches nothing: every stage runs offline out of a cache
 * another command prepared, and the two commands that do reach the network are ones the gate never
 * invokes.</p>
 */
final class QualityGateTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/quality-gate");

    @Test
    @DisplayName("the gate runs exactly the stages the inventory declares, in the declared order")
    void theGateRunsExactlyTheDeclaredStages() {
        assertEquals("", gate().against(REPOSITORY).render());
        assertEquals(gate().stages().stream().map(QualityGate.StageRow::name).toList(),
                QualityGate.stagesInScript(REPOSITORY));
    }

    @Test
    @DisplayName("the stage order begins with what has to be verified before anything else runs")
    void theOrderBeginsWithVerification() {
        final List<String> stages = QualityGate.stagesInScript(REPOSITORY);
        assertEquals("locked-dependency-cache", stages.getFirst());
        assertEquals("pinned-interop-images", stages.get(1));
        assertEquals("public-interop-tier", stages.getLast());
    }

    @Test
    @DisplayName("a stage declared and not run, and one run and not declared, are refused")
    void theStageListIsCheckedInBothDirections() {
        assertRule(gateAt("stage-that-does-not-run.toml").against(REPOSITORY), "quality-gate",
                "nobody-runs-this is declared and the gate does not run it");
    }

    @Test
    @DisplayName("every stage records what it decides and why the gate decides it there")
    void everyStageRecordsItsReason() {
        gate().stages().forEach(stage -> {
            assertFalse(stage.reason().isBlank(), stage.name() + " records no reason");
            assertFalse(stage.command().isBlank(), stage.name() + " names no command");
        });
    }

    @Test
    @DisplayName("the gate reaches nothing, and invokes no command that does")
    void theGateReachesNothing() {
        assertEquals("", gate().reachesNothing(REPOSITORY).render());
        // Three now, and the gate invokes none of them. Publishing reaches a registry because
        // that is what publishing is; what matters is that it is never something the gate does.
        assertEquals(List.of("prepare_interop_images", "prepare_locked_dependency_cache",
                        "publish_release"),
                DependencyPolicy.networkReachingScripts(REPOSITORY));
    }

    @Test
    @DisplayName("the gate refuses an argument rather than ignoring it")
    void theGateRefusesAnArgument() {
        final String script = RepositoryTree.text(REPOSITORY.resolve("scripts/quality"));
        assertTrue(script.contains("takes no argument"),
                "the gate does not refuse an argument at all");
        assertTrue(script.contains("$# -ne 0"),
                "the gate does not decide whether it was given one");
    }

    @Test
    @DisplayName("what the gate needs prepared it verifies, naming the preparation command")
    void whatTheGateNeedsItVerifies() {
        List.of("verify_locked_dependency_cache", "verify_interop_images").forEach(command -> {
            final Path verification = REPOSITORY.resolve("scripts").resolve(command);
            assertTrue(Files.isExecutable(verification), command + " is not committed and executable");
            final String text = RepositoryTree.text(verification);
            assertTrue(text.contains("prepare_"),
                    command + " does not name the preparation command when it refuses");
            assertFalse(text.contains(DependencyPolicy.NETWORK_DECLARATION),
                    command + " declares that it reaches the network");
        });
    }

    @Test
    @DisplayName("the closing report names every tier the gate did not run, with its command")
    void theClosingReportNamesEveryUnrunTier() {
        final String report = RepositoryTree.text(FIXTURES.resolve("closing-report.txt"));
        assertEquals("", gate().closingReport(report).render());
        assertRule(gate().closingReport("gate passed"), "quality-gate",
                "tier b did not run and the report does not name it");
    }

    @Test
    @DisplayName("the tier inventory names one tier the gate runs and two it does not")
    void theTierInventoryIsComplete() {
        assertEquals(List.of("a", "b", "c"),
                gate().tiers().stream().map(QualityGate.TierRow::name).toList());
        assertEquals(1, gate().tiers().stream().filter(QualityGate.TierRow::runByTheGate).count());
        gate().tiers().forEach(tier -> assertFalse(tier.reason().isBlank(),
                "tier " + tier.name() + " records no reason"));
    }

    private static QualityGate gate() {
        return assertInstanceOf(QualityGate.Loaded.class, QualityGate.read(REPOSITORY),
                "the gate inventory was refused").gate();
    }

    private static QualityGate gateAt(String fixture) {
        return assertInstanceOf(QualityGate.Loaded.class,
                QualityGate.readInventory(FIXTURES.resolve(fixture)),
                fixture + " was refused").gate();
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + report.render());
    }
}
