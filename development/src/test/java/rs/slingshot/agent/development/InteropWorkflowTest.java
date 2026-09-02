// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workflow that runs the public tier, held to the same rules as the one that runs the gate.
 *
 * <p>What it needs prepared it prepares in its own steps, before the tier runs, so the tier itself
 * still fetches nothing. And the licensed tiers are not skipped: it says which did not run and the
 * exact command for each, read from the inventory rather than written into the workflow — because a
 * suite that quietly did not run is a suite reporting success it did not earn.</p>
 */
final class InteropWorkflowTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** What the workflow that runs the public tier is called. */
    private static final String INTEROP = "interop.yml";

    @Test
    @DisplayName("the interoperability workflow is held to the same rules as the gate's")
    void thesameRulesApply() {
        assertEquals(List.of(), WorkflowPolicy.findingsIn(workflow()),
                "the workflow that runs containers with credentials is held to fewer rules than"
                        + " the one that runs the gate, and it is the one that runs containers");
    }

    @Test
    @DisplayName("what the tier needs is prepared in its own steps, before the tier runs")
    void thepreparationsComeFirst() {
        final List<String> commands = workflow().shellCommands();
        final int cache = commands.indexOf("scripts/prepare_locked_dependency_cache");
        final int images = commands.indexOf("scripts/prepare_interop_images");
        final int tier = indexOfCommandContaining(commands, "-pl interop");
        assertTrue(cache >= 0 && images >= 0,
                "the workflow does not prepare what the tier needs, so the tier would fetch it and"
                        + " the whole offline arrangement would be a preference: " + commands);
        assertTrue(cache < tier && images < tier,
                "something is prepared after the tier runs, which means the tier fetched it: "
                        + commands);
    }

    @Test
    @DisplayName("a container the harness left behind is a failure rather than an accumulation")
    void aleakedContainerFails() {
        assertTrue(workflow().shellCommands().contains("scripts/verify_interop_images"),
                "nothing checks what the engine still holds afterwards, so a leaked container is a"
                        + " slow accumulation on somebody's runner rather than a build failure");
    }

    @Test
    @DisplayName("which tiers did not run is read from the inventory rather than written in here")
    void thetiersNotRunAreReadFromTheInventory() {
        assertTrue(workflow().shellCommands().contains("scripts/report_tiers_not_run"),
                "the workflow does not say which tiers did not run, and a passing run is not a"
                        + " complete one");
        final String reporter = RepositoryTree.text(REPOSITORY.resolve("scripts/report_tiers_not_run"));
        assertTrue(reporter.contains("quality-gate.toml"),
                "the report is written down rather than read from the inventory, so a tier added"
                        + " later would not be reported");
        assertTrue(!RepositoryTree.text(REPOSITORY.resolve(".github/workflows/" + INTEROP))
                        .contains("interop_quickstart_tier"),
                "the workflow names a licensed tier, and the licensed tiers are kept out entirely"
                        + " rather than attempted and skipped");
    }

    @Test
    @DisplayName("the captured output is published, so a failure is diagnosable without a rerun")
    void thecapturedOutputIsPublished() {
        assertTrue(workflow().actions().stream()
                        .anyMatch(action -> action.contains("upload-artifact")),
                "nothing publishes what the tier captured, so a failure on somebody else's machine"
                        + " is a failure nobody can read");
    }

    private static int indexOfCommandContaining(List<String> commands, String fragment) {
        return java.util.stream.IntStream.range(0, commands.size())
                .filter(at -> commands.get(at).contains(fragment))
                .findFirst()
                .orElse(Integer.MAX_VALUE);
    }

    private static WorkflowPolicy.Workflow workflow() {
        return WorkflowPolicy.workflowsIn(REPOSITORY).stream()
                .filter(held -> held.file().endsWith(INTEROP))
                .findFirst()
                .orElseThrow(() -> new AssertionError("there is no interoperability workflow"));
    }
}
