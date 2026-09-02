// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The analysers a content package and the container are held to, and the container's own contents.
 *
 * <p>Cloud Manager runs Adobe's container analyser before it accepts a deployment, and a finding
 * there is a deployment that does not happen — in somebody else's pipeline, about an artifact this
 * repository built. Running the same analyser here moves that finding to the machine that wrote the
 * code, which is the only place it can be fixed on the day it appears.</p>
 *
 * <p>The task set is data. A task the plugin runs and the policy does not name is a check nobody
 * chose, and a task the policy names and the plugin does not run is a claim about a check that is
 * not happening — so the correspondence runs both ways.</p>
 */
public final class PackageAnalysis {

    private static final String POLICY_FILE = "policy/package-analysis.toml";

    private static final String TASK_ROWS = "analyser_task";

    private static final String VALIDATOR_ROWS = "validator";

    private static final String EMBED_ROWS = "embedded";

    /** How the analyser names a task it is about to run. */
    private static final Pattern EXECUTED_TASK = Pattern.compile("Executing [^\\[]*\\[([a-z0-9-]+)]");

    /** The plugin that carries the validators every content package runs. */
    private static final String PACKAGE_PLUGIN = "filevault-package-maven-plugin";

    private final String analyserArtifact;
    private final String analyserModule;
    private final List<TaskRow> tasks;
    private final List<TaskRow> validators;
    private final List<EmbeddedRow> embedded;

    private PackageAnalysis(String analyserArtifact, String analyserModule, List<TaskRow> tasks,
                            List<TaskRow> validators, List<EmbeddedRow> embedded) {
        this.analyserArtifact = analyserArtifact;
        this.analyserModule = analyserModule;
        this.tasks = tasks;
        this.validators = validators;
        this.embedded = embedded;
    }

    /**
     * One analyser task or validator this build runs.
     *
     * @param name the task's own identifier
     * @param reason what it decides, and why that matters here
     */
    public record TaskRow(String name, String reason) {
    }

    /**
     * One artifact the container embeds, at the place and in the order it installs.
     *
     * @param artifact the artifact identifier
     * @param target where inside the container it sits
     * @param runMode the run mode it installs under
     * @param order where it sits in the install order
     */
    public record EmbeddedRow(String artifact, String target, String runMode, long order) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(PackageAnalysis policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the analysis policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("package-analysis")
                .text("container_analyser.artifact")
                .text("container_analyser.module")
                .answer("container_analyser.fail_on_finding")
                .text("container_analyser.reason")
                .rows(TASK_ROWS, row -> row.text("name").text("reason"))
                .rows(VALIDATOR_ROWS, row -> row.text("name").text("reason"))
                .rows(EMBED_ROWS, row -> row.text("artifact").text("target").text("run_mode")
                        .number("order"))
                .build();
    }

    /**
     * Reads the policy this repository commits.
     *
     * @param root the repository root
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome read(Path root) {
        return readPolicy(root.resolve(POLICY_FILE));
    }

    /**
     * Reads a policy document from wherever it sits.
     *
     * @param policy the policy document
     * @return the policy, or the one reason the document was refused
     */
    public static Outcome readPolicy(Path policy) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(policy, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        if (!document.answer("container_analyser.fail_on_finding")) {
            return new Refused("the policy lets the container analyser report rather than fail");
        }
        final List<TaskRow> tasks = rows(document, TASK_ROWS);
        final List<TaskRow> validators = rows(document, VALIDATOR_ROWS);
        final Optional<TaskRow> unexplained = List.copyOf(tasks).stream()
                .filter(row -> row.reason().isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused(unexplained.get().name() + " is run and records no reason");
        }
        return new Loaded(new PackageAnalysis(document.text("container_analyser.artifact"),
                document.text("container_analyser.module"), tasks, validators,
                document.rows(EMBED_ROWS).stream()
                        .map(row -> new EmbeddedRow(row.text("artifact"), row.text("target"),
                                row.text("run_mode"), row.number("order")))
                        .toList()));
    }

    private static List<TaskRow> rows(PolicyDocument document, String key) {
        return document.rows(key).stream()
                .map(row -> new TaskRow(row.text("name"), row.text("reason")))
                .toList();
    }

    /**
     * Every analyser task the policy declares.
     *
     * @return the tasks, in the policy's own order
     */
    public List<TaskRow> tasks() {
        return Collections.unmodifiableList(tasks);
    }

    /**
     * Every validator the policy declares.
     *
     * @return the validators, in the policy's own order
     */
    public List<TaskRow> validators() {
        return Collections.unmodifiableList(validators);
    }

    /**
     * Everything the container embeds, in the order it installs.
     *
     * @return the embedded rows, in the policy's own order
     */
    public List<EmbeddedRow> embedded() {
        return Collections.unmodifiableList(embedded);
    }

    /**
     * Whether the analyser and the validators are bound the way the policy declares.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module that does not run the analyser or the validators, and one
     *     where either is configured to report rather than to fail
     */
    public PolicyReport binding(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final Optional<Xpp3Dom> analyser =
                reactor.pluginConfiguration(analyserModule, analyserArtifact);
        if (analyser.isEmpty()) {
            findings.add(PolicyFinding.inFile(analyserModule + "/pom.xml", "package-analysis",
                    analyserArtifact + " is declared in the policy and not in the build"));
        }
        analyser.map(Xpp3Dom::toString)
                .filter(configured -> configured.contains("<failOnAnalyserErrors>false<"))
                .map(configured -> PolicyFinding.inFile(analyserModule + "/pom.xml",
                        "package-analysis", "the container analyser reports rather than fails"))
                .ifPresent(findings::add);
        contentPackageModules(reactor).forEach(module -> {
            final Optional<Xpp3Dom> validatorSettings =
                    reactor.pluginConfiguration(module, PACKAGE_PLUGIN);
            if (validatorSettings.isEmpty()) {
                findings.add(PolicyFinding.inFile(module + "/pom.xml", "package-analysis",
                        module + " runs no package validators at all"));
                return;
            }
            if (validatorSettings.get().toString().contains("<failOnDependencyErrors>false<")) {
                findings.add(PolicyFinding.inFile(module + "/pom.xml", "package-analysis",
                        module + " reports a dependency finding rather than failing"));
            }
        });
        return PolicyReport.of(findings);
    }

    /**
     * Whether the analyser ran exactly the task set the policy declares.
     *
     * @param analyserOutput what the analyser wrote while it ran
     * @return one finding per task run and not declared, and per task declared and not run
     */
    public PolicyReport taskSet(String analyserOutput) {
        final List<String> executed = EXECUTED_TASK.matcher(analyserOutput).results()
                .map(match -> match.group(1))
                .distinct()
                .sorted()
                .toList();
        final List<PolicyFinding> findings = new ArrayList<>();
        executed.stream()
                .filter(task -> tasks.stream().noneMatch(row -> row.name().equals(task)))
                .map(task -> PolicyFinding.inFile(POLICY_FILE, "analyser-task",
                        task + " ran and the policy names no such task"))
                .forEach(findings::add);
        tasks.stream()
                .map(TaskRow::name)
                .filter(task -> !executed.contains(task))
                .map(task -> PolicyFinding.inFile(POLICY_FILE, "analyser-task",
                        task + " is declared and the analyser did not run it"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * Whether the container embeds exactly what the policy declares, in the declared order.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per embed with no row, per row with no embed, and per embed whose target
     *     or run mode is not the declared one
     */
    public PolicyReport containerContents(ReactorModel reactor) {
        final List<EmbeddedRow> built = builtEmbeds(reactor);
        final List<PolicyFinding> findings = new ArrayList<>();
        built.stream()
                .filter(embed -> embedded.stream()
                        .noneMatch(row -> row.artifact().equals(embed.artifact())))
                .map(embed -> PolicyFinding.inFile(analyserModule + "/pom.xml", "container-contents",
                        embed.artifact() + " is embedded and the policy names no such row"))
                .forEach(findings::add);
        embedded.forEach(row -> {
            final Optional<EmbeddedRow> match = built.stream()
                    .filter(embed -> embed.artifact().equals(row.artifact()))
                    .findFirst();
            if (match.isEmpty()) {
                findings.add(PolicyFinding.inFile(POLICY_FILE, "container-contents",
                        row.artifact() + " has a row and the container embeds no such artifact"));
                return;
            }
            if (!match.get().target().equals(row.target())) {
                findings.add(PolicyFinding.inFile(analyserModule + "/pom.xml", "container-contents",
                        row.artifact() + " installs at " + match.get().target()
                                + " where the policy declares " + row.target()));
            }
            if (!match.get().runMode().equals(row.runMode())) {
                findings.add(PolicyFinding.inFile(analyserModule + "/pom.xml", "container-contents",
                        row.artifact() + " installs under " + match.get().runMode()
                                + " where the policy declares " + row.runMode()));
            }
        });
        final List<String> declaredOrder = embedded.stream()
                .sorted((first, second) -> Long.compare(first.order(), second.order()))
                .map(EmbeddedRow::artifact)
                .toList();
        final List<String> builtOrder = built.stream().map(EmbeddedRow::artifact).toList();
        if (!builtOrder.isEmpty() && !builtOrder.equals(declaredOrder)) {
            findings.add(PolicyFinding.inFile(analyserModule + "/pom.xml", "container-contents",
                    "the container installs " + builtOrder + " where the policy declares "
                            + declaredOrder));
        }
        return PolicyReport.of(findings);
    }

    /**
     * Everything the container's own build configuration says it embeds.
     *
     * @param reactor the reactor as the build resolved it
     * @return the embedded artifacts, in the order the configuration lists them
     */
    public List<EmbeddedRow> builtEmbeds(ReactorModel reactor) {
        return reactor.pluginConfiguration(analyserModule, PACKAGE_PLUGIN)
                .map(configuration -> configuration.getChild("embeddeds"))
                .map(PackageAnalysis::readEmbeds)
                .orElseGet(List::of);
    }

    private static List<EmbeddedRow> readEmbeds(Xpp3Dom embeds) {
        final Xpp3Dom[] children = embeds.getChildren();
        return IntStream.range(0, children.length)
                .mapToObj(index -> new EmbeddedRow(childValue(children[index], "artifactId"),
                        childValue(children[index], "target"),
                        childValue(children[index], "runMode"),
                        index))
                .toList();
    }

    private static String childValue(Xpp3Dom parent, String name) {
        final Xpp3Dom child = parent.getChild(name);
        return child == null || child.getValue() == null ? "" : child.getValue().strip();
    }

    private static List<String> contentPackageModules(ReactorModel reactor) {
        return reactor.modules().stream()
                .filter(module -> "content-package".equals(reactor.effective(module).getPackaging()))
                .toList();
    }
}
