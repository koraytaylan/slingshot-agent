// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The gate, stage by stage, held to the inventory that declares it.
 *
 * <p>The correspondence runs both ways for the reason it always does here: a stage the script runs
 * and nobody declared is a check nobody chose, and a stage somebody declared and the script does
 * not run is a claim about a check that is not happening. Either one on its own lets the inventory
 * drift away from the gate it describes.</p>
 *
 * <p>The other thing decided here is what the gate does not do. It reaches nothing — every stage
 * runs offline out of a cache another command prepared — and it says at the end which tiers it did
 * not run and the exact command for each, so a passing gate is never mistaken for a complete
 * one.</p>
 */
public final class QualityGate {

    private static final String POLICY_FILE = "policy/quality-gate.toml";

    private static final String GATE_SCRIPT = "scripts/quality";

    private static final String STAGE_ROWS = "stage";

    private static final String TIER_ROWS = "tier";

    /** How the gate script names a stage it is about to run. */
    private static final Pattern SCRIPT_STAGE =
            Pattern.compile("^stage ([a-z-]+) ", Pattern.MULTILINE);

    /** How the gate reaches the build, which is offline and out of the prepared cache. */
    private static final String OFFLINE_BUILD = "--offline";

    private final List<StageRow> stages;
    private final List<TierRow> tiers;

    private QualityGate(List<StageRow> stages, List<TierRow> tiers) {
        this.stages = stages;
        this.tiers = tiers;
    }

    /**
     * One stage the gate runs, in the order it runs them.
     *
     * @param name the stage's own name
     * @param command what runs it
     * @param reason what it decides, and why the gate decides it there
     */
    public record StageRow(String name, String command, String reason) {
    }

    /** Whether the gate runs a tier, or names it at the end as one it did not. */
    public enum GateMembership {
        /** The gate runs this tier, so a passing gate has run it. */
        RUN_HERE,
        /** The gate does not run this tier, and says so at the end with the command that does. */
        RUN_ELSEWHERE
    }

    /**
     * One interoperability tier, and whether this gate runs it.
     *
     * @param name the tier's own letter
     * @param title what the tier is
     * @param membership whether the gate runs it
     * @param command the exact command that runs it
     * @param reason why it is or is not part of the gate
     */
    public record TierRow(String name, String title, GateMembership membership, String command,
                          String reason) {

        /**
         * Whether the gate runs this tier.
         *
         * @return whether a passing gate has run it
         */
        public boolean runByTheGate() {
            return membership == GateMembership.RUN_HERE;
        }
    }

    /** The result of reading the inventory: the inventory, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * An inventory that satisfied its shape completely.
     *
     * @param gate the loaded inventory
     */
    public record Loaded(QualityGate gate) implements Outcome {
    }

    /**
     * A read that produced no inventory.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the gate inventory is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("quality-gate")
                .rows(STAGE_ROWS, row -> row.text("name").text("command").text("reason"))
                .rows(TIER_ROWS, row -> row.text("name").text("title").answer("run_by_the_gate")
                        .text("command").text("reason"))
                .build();
    }

    /**
     * Reads the inventory this repository commits.
     *
     * @param root the repository root
     * @return the inventory, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readInventory(root.resolve(POLICY_FILE));
    }

    /**
     * Reads an inventory from wherever it sits.
     *
     * @param inventory the inventory document
     * @return the inventory, or the one reason the document was refused
     */
    public static Outcome readInventory(Path inventory) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(inventory, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        final List<StageRow> stages = document.rows(STAGE_ROWS).stream()
                .map(row -> new StageRow(row.text("name"), row.text("command"), row.text("reason")))
                .toList();
        final Optional<StageRow> unexplained = stages.stream()
                .filter(row -> row.reason().isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused(unexplained.get().name() + " is a stage and records no reason");
        }
        final List<TierRow> tiers = document.rows(TIER_ROWS).stream()
                .map(row -> new TierRow(row.text("name"), row.text("title"),
                        row.answer("run_by_the_gate")
                                ? GateMembership.RUN_HERE : GateMembership.RUN_ELSEWHERE,
                        row.text("command"), row.text("reason")))
                .toList();
        if (tiers.stream().noneMatch(TierRow::runByTheGate)) {
            return new Refused("the inventory declares no tier the gate runs at all");
        }
        return new Loaded(new QualityGate(stages, tiers));
    }

    /**
     * Every stage the gate declares, in the order it declares them.
     *
     * @return the stage rows
     */
    public List<StageRow> stages() {
        return Collections.unmodifiableList(stages);
    }

    /**
     * Every interoperability tier this repository has.
     *
     * @return the tier rows, in the inventory's own order
     */
    public List<TierRow> tiers() {
        return Collections.unmodifiableList(tiers);
    }

    /**
     * The stages the gate script actually runs, in the order it runs them.
     *
     * @param root the repository root
     * @return the stage names the script names
     */
    public static List<String> stagesInScript(Path root) {
        return SCRIPT_STAGE.matcher(RepositoryTree.text(root.resolve(GATE_SCRIPT))).results()
                .map(match -> match.group(1))
                .toList();
    }

    /**
     * Whether the gate runs exactly the stages it declares, in the declared order.
     *
     * @param root the repository root
     * @return one finding per stage run and not declared, per stage declared and not run, and one
     *     where the order differs
     */
    public PolicyReport against(Path root) {
        final List<String> run = stagesInScript(root);
        final List<String> declared = stages.stream().map(StageRow::name).toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        run.stream()
                .filter(stage -> !declared.contains(stage))
                .map(stage -> PolicyFinding.inFile(POLICY_FILE, "quality-gate",
                        stage + " runs and the inventory declares no such stage"))
                .forEach(findings::add);
        declared.stream()
                .filter(stage -> !run.contains(stage))
                .map(stage -> PolicyFinding.inFile(GATE_SCRIPT, "quality-gate",
                        stage + " is declared and the gate does not run it"))
                .forEach(findings::add);
        if (findings.isEmpty() && !run.equals(declared)) {
            findings.add(PolicyFinding.inFile(GATE_SCRIPT, "quality-gate",
                    "the gate runs " + run + " where the inventory declares " + declared));
        }
        return PolicyReport.of(findings);
    }

    /**
     * Whether the gate reaches anything, and whether every command it invokes is one that does not.
     *
     * @param root the repository root
     * @return one finding where the gate declares it reaches the network, and one per command it
     *     invokes that does
     */
    public PolicyReport reachesNothing(Path root) {
        final String script = RepositoryTree.text(root.resolve(GATE_SCRIPT));
        final List<PolicyFinding> findings = new ArrayList<>();
        if (script.contains(DependencyPolicy.NETWORK_DECLARATION)) {
            findings.add(PolicyFinding.inFile(GATE_SCRIPT, "quality-gate",
                    "the gate declares that it reaches the network"));
        }
        if (!script.contains(OFFLINE_BUILD)) {
            findings.add(PolicyFinding.inFile(GATE_SCRIPT, "quality-gate",
                    "the gate reaches the build without " + OFFLINE_BUILD + ", so a stage could fetch"));
        }
        DependencyPolicy.networkReachingScripts(root).stream()
                .filter(script::contains)
                .map(command -> PolicyFinding.inFile(GATE_SCRIPT, "quality-gate",
                        "the gate invokes " + command + ", which reaches the network"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether the gate's closing report names every tier it did not run.
     *
     * @param closingReport what the gate wrote when it finished
     * @return one finding per tier the gate does not run and does not name, and per tier whose
     *     command the report does not give
     */
    public PolicyReport closingReport(String closingReport) {
        return PolicyReport.of(tiers.stream()
                .filter(tier -> !tier.runByTheGate())
                .filter(tier -> !closingReport.contains(tier.command())
                        || !closingReport.contains(tier.title()))
                .map(tier -> PolicyFinding.inFile(GATE_SCRIPT, "quality-gate",
                        "tier " + tier.name() + " did not run and the report does not name it"
                                + " with its command " + tier.command()))
                .toList());
    }
}
