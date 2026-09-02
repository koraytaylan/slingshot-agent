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
 * The workflow that publishes, held to the same rules as the two that do not.
 *
 * <p>It is the one that runs with the most credentials and the one somebody is most tempted to make
 * an exception for, which is exactly why there is no exception.</p>
 *
 * <p>The rule with the most in it is about provenance: exactly one job may say what the bytes are,
 * and that job may not also be able to change them. Two jobs holding the permission is not twice as
 * much provenance — it is a second place the statement can be made from, and the whole value of the
 * statement is that there is one.</p>
 */
final class ReleaseWorkflowTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** Where the attestation arrangement is declared. */
    private static final String POLICY = "support/release-attestation-policy.toml";

    /** What the release workflow is called. */
    private static final String RELEASE = "release.yml";

    @Test
    @DisplayName("the release workflow is held to the same rules as every other")
    void thesameRulesApply() {
        assertEquals(List.of(), WorkflowPolicy.findingsIn(workflow()),
                "the workflow that runs with the most credentials is held to fewer rules than the"
                        + " ones that run with the fewest");
    }

    @Test
    @DisplayName("exactly one job may say what the bytes are, and it is named")
    void oneJobMaySayWhatTheBytesAre() {
        final String policy = RepositoryTree.text(REPOSITORY.resolve(POLICY));
        final String workflow = RepositoryTree.text(REPOSITORY.resolve(
                ".github/workflows/" + RELEASE));
        assertTrue(policy.contains("job = \"attest\""),
                "no job is named as the one that may attest, so any of them may");
        assertEquals(1, workflow.lines().filter(line -> line.contains("attestations: write"))
                        .count(),
                "more than one job holds the attestation permission, and a second place the"
                        + " statement can be made from is not twice as much provenance");
    }

    @Test
    @DisplayName("the job that says what the bytes are cannot change them")
    void theattestingJobCannotChangeWhatItDescribes() {
        final String workflow = RepositoryTree.text(REPOSITORY.resolve(
                ".github/workflows/" + RELEASE));
        final String attesting = workflow.substring(workflow.indexOf("  attest:"));
        assertTrue(!attesting.contains("contents: write") && !attesting.contains("packages: write"),
                "the job that attests can also change what it is describing, and a statement made"
                        + " by the thing it is about is not a statement: " + attesting);
    }

    @Test
    @DisplayName("one built set goes to both targets rather than one build per target")
    void onebuiltSetGoesToBothTargets() {
        final List<String> commands = workflow().shellCommands();
        assertEquals(1, commands.stream()
                        .filter(command -> command.contains("build_release_artifacts")).count(),
                "the release builds more than once, and two builds are two sets of bytes under one"
                        + " version: " + commands);
        assertTrue(commands.stream()
                        .anyMatch(command -> command.contains("verify_release_artifacts")),
                "nothing proves two builds of this source agree, which is what makes the digests"
                        + " worth publishing");
    }

    @Test
    @DisplayName("a release whose preconditions are unmet fails before anything is built")
    void thepreconditionsAreCheckedFirst() {
        final List<String> commands = workflow().shellCommands();
        final int checks = indexOfCommandContaining(commands, "PublicationAuthorityTest");
        final int builds = indexOfCommandContaining(commands, "build_release_artifacts");
        assertTrue(checks < builds,
                "the release builds before it checks whether it may publish, which produces"
                        + " artifacts nobody may distribute: " + commands);
    }

    private static int indexOfCommandContaining(List<String> commands, String fragment) {
        return java.util.stream.IntStream.range(0, commands.size())
                .filter(at -> commands.get(at).contains(fragment))
                .findFirst()
                .orElse(Integer.MAX_VALUE);
    }

    private static WorkflowPolicy.Workflow workflow() {
        return WorkflowPolicy.workflowsIn(REPOSITORY).stream()
                .filter(held -> held.file().endsWith(RELEASE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("there is no release workflow"));
    }
}
