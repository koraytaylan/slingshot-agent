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
 * Every fuzz target this repository declares, against the targets and corpora that exist.
 *
 * <p>The one worth having is the corpus check. A target with no corpus runs, reports nothing, and
 * looks exactly like a target that found nothing — which is the failure mode a fuzzing suite is
 * most likely to have and least likely to notice.</p>
 */
final class FuzzTargetInventoryTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/fuzz-target");

    @Test
    @DisplayName("every declared target exists, is declared, and has a committed corpus")
    void thisRepositoryHoldsEveryTargetItDeclares() {
        assertEquals("", inventory(REPOSITORY).against(REPOSITORY).render());
    }

    @Test
    @DisplayName("a declared target whose class does not exist is refused, naming it")
    void atargetWithNoClassIsRefused() {
        final PolicyReport report = inventory(FIXTURES.resolve("target-with-no-class.toml"))
                .against(REPOSITORY);
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> FuzzTargetInventory.TARGET_WITH_NO_CLASS
                                .equals(finding.rule())
                                && finding.symbol().contains("nothing-like-this")),
                "a target naming a class that does not exist was accepted: " + report.render());
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> FuzzTargetInventory.TARGET_WITH_NO_CORPUS
                                .equals(finding.rule())),
                "a target with no committed corpus was accepted, and one of those runs, reports"
                        + " nothing, and looks exactly like a target that found nothing");
    }

    @Test
    @DisplayName("a target class the declaration does not name is refused, and so is a stray corpus")
    void bothDirectionsAreRefused() {
        final PolicyReport report = inventory(FIXTURES.resolve("target-with-no-class.toml"))
                .against(REPOSITORY);
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> FuzzTargetInventory.CLASS_WITH_NO_TARGET
                                .equals(finding.rule())),
                "a target class nothing declares was accepted, and the runner cannot be pointed"
                        + " at one: " + report.render());
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> FuzzTargetInventory.CORPUS_WITH_NO_TARGET
                                .equals(finding.rule())),
                "a corpus no target consumes was accepted, so it would rot silently");
    }

    @Test
    @DisplayName("every declared target's corpus holds something")
    void everycorpusHoldsSomething() {
        final FuzzTargetInventory inventory = inventory(REPOSITORY);
        assertTrue(!inventory.targets().isEmpty(), "nothing is declared, so this proves nothing");
        inventory.targets().forEach(target -> assertTrue(
                !FuzzTargetInventory.corpusOf(REPOSITORY, target).isEmpty(),
                target.name() + " has an empty corpus"));
    }

    @Test
    @DisplayName("the four rules are four, so a finding says which thing to fix")
    void thefourRulesAreDistinct() {
        assertEquals(4, List.of(FuzzTargetInventory.TARGET_WITH_NO_CLASS,
                        FuzzTargetInventory.CLASS_WITH_NO_TARGET,
                        FuzzTargetInventory.TARGET_WITH_NO_CORPUS,
                        FuzzTargetInventory.CORPUS_WITH_NO_TARGET).stream().distinct().count(),
                "two of the rules are spelled the same way");
    }

    private static FuzzTargetInventory inventory(Path where) {
        return assertInstanceOf(FuzzTargetInventory.Loaded.class,
                java.nio.file.Files.isDirectory(where)
                        ? FuzzTargetInventory.read(where) : FuzzTargetInventory.readFile(where),
                "the fuzzing declaration did not read").inventory();
    }
}
