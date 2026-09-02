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
 * What a workflow may do, since every one of them runs with credentials on somebody else's machine.
 *
 * <p>Four rules and each is a way of stopping being true quietly: an action that moves under a tag
 * its owner controls, a permission granted once and inherited forever, a checkout token left on the
 * runner, and an expression interpolated into a shell by a workflow that reads perfectly well.</p>
 */
final class WorkflowPolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/workflow-policy");

    @Test
    @DisplayName("every workflow this repository commits holds all five rules")
    void everyworkflowHoldsTheRules() {
        assertEquals("", WorkflowPolicy.across(REPOSITORY).render());
        assertTrue(!WorkflowPolicy.workflowsIn(REPOSITORY).isEmpty(),
                "there are no workflows, so this proves nothing");
    }

    @Test
    @DisplayName("an action that can move under its own name is refused")
    void anunpinnedActionIsRefused() {
        assertRule("unpinned-action.yml", WorkflowPolicy.AN_UNPINNED_ACTION);
    }

    @Test
    @DisplayName("a permission every job inherits is refused, one finding per permission")
    void aworkflowWidePermissionIsRefused() {
        assertEquals(2, findings("workflow-wide-permission.yml").stream()
                        .filter(finding -> WorkflowPolicy.A_WORKFLOW_WIDE_PERMISSION
                                .equals(finding.rule()))
                        .count(),
                "two permissions were reported as one finding, and a job added later starts with"
                        + " both");
    }

    @Test
    @DisplayName("a checkout that leaves its token on the runner is refused")
    void apersistedCredentialIsRefused() {
        assertRule("persisted-credential.yml", WorkflowPolicy.A_PERSISTED_CREDENTIAL);
    }

    @Test
    @DisplayName("an expression interpolated into a shell is refused, and through the environment is not")
    void anexpressionInAShellIsRefused() {
        assertRule("expression-in-a-shell.yml", WorkflowPolicy.AN_EXPRESSION_IN_A_SHELL);
        assertEquals(List.of(), findings("accepted.yml"),
                "passing a value through the environment was refused, and it is the fix rather"
                        + " than the mistake");
    }

    @Test
    @DisplayName("the quality workflow runs the gate and its preparations, and nothing else")
    void thequalityWorkflowRunsTheGate() {
        final WorkflowPolicy.Workflow quality = WorkflowPolicy.workflowsIn(REPOSITORY).stream()
                .filter(workflow -> workflow.file().endsWith("quality.yml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("there is no quality workflow"));
        assertTrue(quality.shellCommands().contains(WorkflowPolicy.THE_GATE),
                "the quality workflow does not run the gate: " + quality.shellCommands());
        assertTrue(WorkflowPolicy.findingsIn(quality).stream()
                        .noneMatch(finding -> WorkflowPolicy.MORE_THAN_THE_GATE
                                .equals(finding.rule())),
                "something runs beside the gate, so this workflow's result is not the gate's");
    }

    @Test
    @DisplayName("a full commit is pinned and a tag, a branch, and a short commit are not")
    void onlyafullCommitIsPinned() {
        assertTrue(WorkflowPolicy.isPinned(
                "actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683"));
        assertTrue(WorkflowPolicy.isPinned("./.github/actions/local"));
        org.junit.jupiter.api.Assertions.assertFalse(
                WorkflowPolicy.isPinned("actions/checkout@v4"));
        org.junit.jupiter.api.Assertions.assertFalse(
                WorkflowPolicy.isPinned("actions/checkout@main"));
        org.junit.jupiter.api.Assertions.assertFalse(
                WorkflowPolicy.isPinned("actions/checkout@11bd719"),
                "a short commit was called pinned, and a short one can become ambiguous");
    }

    private static void assertRule(String fixture, String rule) {
        assertTrue(findings(fixture).stream().anyMatch(finding -> rule.equals(finding.rule())),
                fixture + " was not refused under " + rule + ": " + findings(fixture));
    }

    private static List<PolicyFinding> findings(String fixture) {
        return WorkflowPolicy.findingsIn(WorkflowPolicy.read(fixture,
                RepositoryTree.text(FIXTURES.resolve(fixture))));
    }
}
