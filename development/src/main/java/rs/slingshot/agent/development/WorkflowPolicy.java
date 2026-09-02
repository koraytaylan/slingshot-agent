// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * What a workflow may do, read out of its own structure rather than out of its text.
 *
 * <p>Every workflow here runs with credentials on somebody else's machine, which is a different
 * kind of thing from the rest of this repository. Four rules, and each of them is a way that stops
 * being true quietly: an action that moves under a tag somebody else controls, a permission granted
 * once and inherited forever, a checkout token left on the runner for a later step to find, and an
 * expression interpolated into a shell by a workflow that reads perfectly well.</p>
 *
 * <p>Read structurally because the difference between a value and a place is structural. An action
 * reference in a comment explaining why references are pinned is not a reference; a permission
 * named in a job is not the same as one named at the workflow. A text search cannot tell either
 * apart, and a rule that could not would be a rule nobody could document.</p>
 */
public final class WorkflowPolicy {

    /** Where the workflows sit. */
    public static final String WORKFLOWS = ".github/workflows";

    /** The rule an action that can move under its own name is reported under. */
    public static final String AN_UNPINNED_ACTION = "an-unpinned-action";

    /** The rule a permission granted where every job inherits it is reported under. */
    public static final String A_WORKFLOW_WIDE_PERMISSION = "a-workflow-wide-permission";

    /** The rule a checkout that leaves its token on the runner is reported under. */
    public static final String A_PERSISTED_CREDENTIAL = "a-persisted-credential";

    /** The rule an expression that reaches a shell is reported under. */
    public static final String AN_EXPRESSION_IN_A_SHELL = "an-expression-in-a-shell";

    /** The rule a quality workflow that runs anything but the gate is reported under. */
    public static final String MORE_THAN_THE_GATE = "more-than-the-gate";

    /** How long a full commit is, which is the only reference that cannot move. */
    private static final int A_FULL_COMMIT = 40;

    /** What an action from this repository begins with, which is pinned by being here. */
    private static final String LOCAL_ACTION = "./";

    /** The base a commit is written in, which is what makes each character a half-byte. */
    private static final int HEXADECIMAL = 16;

    /** How long the marker that says a line begins a sequence item is, including its space. */
    private static final int A_SEQUENCE_MARKER = 2;

    /** How an expression opens, which in a shell is a value somebody else chose. */
    private static final String OPENS_AN_EXPRESSION = "${{";

    /** The one command the quality workflow runs, after checkout and setup. */
    public static final String THE_GATE = "scripts/quality";

    /** What a step may run in the quality workflow besides the gate, and why. */
    private static final List<String> PREPARATIONS =
            List.of("scripts/prepare_locked_dependency_cache", "scripts/prepare_interop_images");

    private WorkflowPolicy() {
    }

    /**
     * One workflow, as the parts these rules are about.
     *
     * @param file where it sits, relative to the repository root
     * @param workflowPermissions what every job inherits, which should be nothing
     * @param actions every action a step uses, in the order the steps use them
     * @param checkoutPersistence what each checkout says about keeping its token
     * @param shellCommands every command a step runs
     */
    public record Workflow(String file, SequencedMap<String, String> workflowPermissions,
                           List<String> actions, List<String> checkoutPersistence,
                           List<String> shellCommands) {

        /** Holds a workflow nothing can change afterwards. */
        public Workflow {
            workflowPermissions = new LinkedHashMap<>(workflowPermissions);
            actions = List.copyOf(actions);
            checkoutPersistence = List.copyOf(checkoutPersistence);
            shellCommands = List.copyOf(shellCommands);
        }

        /**
         * What every job inherits, as a view nothing can change.
         *
         * @return the permissions granted at the workflow, by name
         */
        @Override
        public SequencedMap<String, String> workflowPermissions() {
            return java.util.Collections.unmodifiableSequencedMap(workflowPermissions);
        }
    }

    /**
     * Every workflow this repository commits.
     *
     * @param root the repository root
     * @return the workflows, in the directory's own order
     */
    public static List<Workflow> workflowsIn(Path root) {
        final Path directory = root.resolve(WORKFLOWS);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        return RepositoryTree.filesUnder(directory, ".yml").stream()
                .sorted()
                .map(file -> read(root.relativize(file).toString(), RepositoryTree.text(file)))
                .toList();
    }

    /**
     * One workflow, read out of its own block structure.
     *
     * <p>Not a general reader. It knows the four things these rules are about and where each of
     * them sits, which is what lets a permission at the workflow be told from one in a job — the
     * distinction a text search cannot make and the whole reason these rules exist.</p>
     *
     * @param file how a finding names it
     * @param document the workflow's text
     * @return the workflow
     */
    public static Workflow read(String file, String document) {
        return new Workflow(file, workflowPermissionsIn(document), valuesOf(document, "uses:"),
                valuesOf(document, "persist-credentials:"), valuesOf(document, "run:"));
    }

    /**
     * What every job inherits, which is the one thing that depends on where a line sits.
     *
     * <p>A permission at the top of the file is granted to every job; the same words inside a job
     * are granted to one. Nothing but position tells them apart, which is why this is read
     * positionally and everything else is not.</p>
     *
     * @param document the workflow's text
     * @return the permissions granted at the workflow, by name
     */
    private static SequencedMap<String, String> workflowPermissionsIn(String document) {
        final SequencedMap<String, String> granted = new LinkedHashMap<>();
        boolean insidePermissions = false;
        for (final String line : document.lines().toList()) {
            final String stripped = withoutSequenceMarker(stripComment(line).strip());
            if (stripped.isEmpty()) {
                continue;
            }
            if (indentOf(line) == 0) {
                if ("jobs:".equals(stripped)) {
                    return granted;
                }
                insidePermissions = stripped.startsWith("permissions:")
                        && !stripped.endsWith("{}");
            } else if (insidePermissions && stripped.contains(":")) {
                granted.put(before(stripped), after(stripped));
            }
        }
        return granted;
    }

    /**
     * Every value one key carries, wherever in the file it is.
     *
     * <p>Position does not matter for these: an action is an action and a command is a command
     * whichever job holds it, and a rule about them is a rule about all of them.</p>
     *
     * @param document the workflow's text
     * @param key the key, including its colon
     * @return the values, in the order they appear
     */
    private static List<String> valuesOf(String document, String key) {
        final List<String> values = new ArrayList<>();
        document.lines()
                .map(line -> withoutSequenceMarker(stripComment(line).strip()))
                .filter(line -> line.startsWith(key))
                .forEach(line -> values.add(after(line)));
        return values;
    }

    /**
     * Every workflow, held to all five rules.
     *
     * @param root the repository root
     * @return one finding per rule any workflow breaks
     */
    public static PolicyReport across(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        workflowsIn(root).forEach(workflow -> findings.addAll(findingsIn(workflow)));
        return PolicyReport.of(findings);
    }

    /**
     * One workflow, held to all five rules.
     *
     * @param workflow the workflow
     * @return one finding per rule it breaks
     */
    public static List<PolicyFinding> findingsIn(Workflow workflow) {
        final List<PolicyFinding> findings = new ArrayList<>();
        workflow.actions().stream()
                .filter(action -> !isPinned(action))
                .forEach(action -> findings.add(PolicyFinding.inFile(workflow.file(),
                        AN_UNPINNED_ACTION, action + " can move under its own name, so what runs"
                                + " here is whatever its owner points it at next")));
        workflow.workflowPermissions().forEach((permission, granted) ->
                findings.add(PolicyFinding.inFile(workflow.file(), A_WORKFLOW_WIDE_PERMISSION,
                        permission + " is granted where every job inherits it, so a job added later"
                                + " starts with it rather than with nothing")));
        workflow.checkoutPersistence().stream()
                .filter(persisted -> !"false".equals(persisted))
                .forEach(persisted -> findings.add(PolicyFinding.inFile(workflow.file(),
                        A_PERSISTED_CREDENTIAL, "a checkout leaves its token on the runner, and a"
                                + " later step that wanted it is a later step nobody granted it")));
        workflow.shellCommands().stream()
                .filter(command -> command.contains(OPENS_AN_EXPRESSION))
                .forEach(command -> findings.add(PolicyFinding.inFile(workflow.file(),
                        AN_EXPRESSION_IN_A_SHELL, command + " interpolates a value into a shell,"
                                + " and the value is one somebody else chose")));
        findings.addAll(gateFindings(workflow));
        return findings;
    }

    /**
     * Whether the quality workflow runs the gate and nothing else.
     *
     * <p>The gate takes no argument for a reason: a run somebody could shorten is a run whose
     * result depends on how it was invoked. A workflow that ran the gate and then a few more things
     * would have a result that is not the gate's result.</p>
     *
     * @param workflow the workflow
     * @return one finding per extra command
     */
    private static List<PolicyFinding> gateFindings(Workflow workflow) {
        if (!workflow.file().endsWith("quality.yml")) {
            return List.of();
        }
        final List<PolicyFinding> findings = new ArrayList<>();
        if (!workflow.shellCommands().contains(THE_GATE)) {
            findings.add(PolicyFinding.inFile(workflow.file(), MORE_THAN_THE_GATE,
                    "the quality workflow does not run " + THE_GATE + " at all"));
        }
        workflow.shellCommands().stream()
                .filter(command -> !THE_GATE.equals(command) && !PREPARATIONS.contains(command))
                .forEach(command -> findings.add(PolicyFinding.inFile(workflow.file(),
                        MORE_THAN_THE_GATE, command + " runs beside the gate, so this workflow's"
                                + " result is not the gate's result")));
        return findings;
    }

    /**
     * Whether one action reference names something that cannot move.
     *
     * @param action the reference
     * @return whether it is pinned
     */
    public static boolean isPinned(String action) {
        if (action.startsWith(LOCAL_ACTION)) {
            return true;
        }
        final Optional<String> reference = action.contains("@")
                ? Optional.of(action.substring(action.lastIndexOf('@') + 1)) : Optional.empty();
        return reference.filter(held -> held.length() == A_FULL_COMMIT)
                .filter(held -> held.chars().allMatch(WorkflowPolicy::isHexadecimal))
                .isPresent();
    }

    private static boolean isHexadecimal(int character) {
        return Character.digit(character, HEXADECIMAL) >= 0;
    }

    /**
     * One line without the dash that says it begins an item.
     *
     * <p>A step is an item in a sequence, so what says what a step does sits after a dash. Reading
     * the dash off is the difference between seeing a step's action and seeing nothing at all.</p>
     *
     * @param line the stripped line
     * @return the line without its marker
     */
    private static String withoutSequenceMarker(String line) {
        return line.startsWith("- ") ? line.substring(A_SEQUENCE_MARKER).strip() : line;
    }

    private static int indentOf(String line) {
        return line.length() - line.stripLeading().length();
    }

    /**
     * One line without whatever a person wrote after a hash.
     *
     * <p>A hash inside a value is a character rather than a comment, so a line whose value has
     * already begun is left alone. A rule that could not tell the two apart would refuse a
     * workflow for the paragraph explaining why it is written the way it is.</p>
     *
     * @param line the line
     * @return the line without its comment
     */
    private static String stripComment(String line) {
        final int hash = line.indexOf('#');
        if (hash < 0) {
            return line;
        }
        final String before = line.substring(0, hash);
        return before.contains(":") || before.contains("'") ? line : before;
    }

    private static String before(String line) {
        return line.substring(0, line.indexOf(':')).strip();
    }

    private static String after(String line) {
        return line.substring(line.indexOf(':') + 1).strip();
    }
}
