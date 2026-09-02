// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every scenario this repository declares, and whether the running instance proves what is served.
 *
 * <p>"Every feature brings its own interoperability test" is either a rule a check enforces or a
 * sentence in a contributing guide that stops being true in about a month. This is the first one.
 * It compares the declared scenarios with the classes that exist in both directions, and it
 * compares them with the features that are actually served — read out of the registrations in the
 * product's own sources rather than out of a list somebody maintains here — so that the day a route
 * starts being served with no scenario behind it, the gate refuses and names it.</p>
 *
 * <p>A scenario is a route scenario or a property scenario. A route scenario names a route the
 * committed table declares; a property scenario names a committed policy it proves on a running
 * instance, because some of what has to hold on somebody else's deployment — what the agent's own
 * identity may do, for one — is not a route at all and would otherwise be proved nowhere.</p>
 */
public final class ScenarioInventory {

    /** The rule every finding here is reported under. */
    public static final String RULE = "interop-coverage";

    /** Where a scenario declares itself, one file per scenario. */
    public static final String SCENARIOS = "interop/scenarios";

    /** The committed route table, which is the feature inventory this commit compares against. */
    public static final String ROUTES = "policy/agent-routes.toml";

    /** Where a scenario's runner class lives. */
    public static final String RUNNERS = "interop/src/test/java";

    /** How a servlet declares the route it serves, which is what makes a feature served. */
    private static final Pattern REGISTERED =
            Pattern.compile("sling\\.servlet\\.paths\\s*=\\s*([^\"\\s,]+)");

    /** What a runner class is called, so a class with no row can be found without a list. */
    private static final String RUNNER_SUFFIX = "Scenario.java";

    private static final String ROUTE_KIND = "route";

    private static final String PROPERTY_KIND = "property";

    /**
     * The kind a scenario has when what it proves is one command.
     *
     * <p>A command is neither a route nor a property. It is reached through a route every other
     * command is reached through, so calling it a route scenario would say nothing about which
     * command it proves, and there is no policy file named after it. Its feature is its own wire
     * name, which the registry declares — so a scenario naming a command nobody registered is
     * refused here, the same way a scenario naming a route nobody serves already is.</p>
     */
    private static final String COMMAND_KIND = "command";

    /** Where the registry declares one file per command, which a command scenario names. */
    /** The registry directory's own name, beside the routes table this check already reads. */
    private static final String REGISTRY_NAME = "commands";

    private final List<ScenarioRow> scenarios;

    private ScenarioInventory(List<ScenarioRow> scenarios) {
        this.scenarios = scenarios;
    }

    /**
     * One declared scenario, as its own file states it.
     *
     * @param file the repository-relative path of the file that declares it
     * @param identifier the scenario's own identifier
     * @param kind {@code route} or {@code property}
     * @param feature the route or the policy it covers
     * @param tier the tier it needs
     * @param deployments the deployment rows it applies to
     * @param runner the fully qualified class that runs it
     * @param claims the outcome categories this scenario proves across every row declaring them,
     *     which is empty for every scenario that proves one feature rather than a property of all
     *     of them
     */
    public record ScenarioRow(String file, String identifier, String kind, String feature,
                              String tier, List<String> deployments, String runner,
                              List<String> claims) {

        /** Holds a row whose lists nothing can change afterwards. */
        public ScenarioRow {
            deployments = List.copyOf(deployments);
            claims = List.copyOf(claims);
        }
    }

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param scenarios the directory one scenario file per scenario sits in
     * @param routes the committed route table, read as a feature inventory
     * @param gate the quality-gate inventory, which is where the tiers are declared
     * @param deployments the deployment matrix, which is where the rows are declared
     * @param registrations the source roots a served route is registered in
     * @param runners the source root a scenario's runner class sits under
     */
    public record Sources(Path scenarios, Path routes, Path gate, Path deployments,
                          List<Path> registrations, Path runners) {

        /** Holds sources whose registration roots nothing can change afterwards. */
        public Sources {
            registrations = List.copyOf(registrations);
        }

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(SCENARIOS), root.resolve(ROUTES),
                    root.resolve("policy/quality-gate.toml"),
                    root.resolve("support/deployments.toml"),
                    List.of(root.resolve("core/src/main/java"), root.resolve("aem/src/main/java")),
                    root.resolve(RUNNERS));
        }

        /**
         * The same sources with the scenarios read from somewhere else.
         *
         * @param elsewhere where the scenario files sit instead
         * @return the sources
         */
        public Sources withScenarios(Path elsewhere) {
            return new Sources(elsewhere, routes, gate, deployments, registrations, runners);
        }

        /**
         * The same sources with the routes and the registrations read from somewhere else.
         *
         * @param table where the route table sits instead
         * @param registered where the registrations sit instead
         * @return the sources
         */
        public Sources withFeatures(Path table, Path registered) {
            return new Sources(scenarios, table, gate, deployments, List.of(registered), runners);
        }

        /**
         * The same sources with the runner classes read from somewhere else.
         *
         * @param elsewhere where the runner classes sit instead
         * @return the sources
         */
        public Sources withRunners(Path elsewhere) {
            return new Sources(scenarios, routes, gate, deployments, registrations, elsewhere);
        }
    }

    /** The result of reading the inventory: the inventory, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * An inventory whose every file satisfied its shape.
     *
     * @param inventory the loaded inventory
     */
    public record Loaded(ScenarioInventory inventory) implements Outcome {
    }

    /**
     * A read that produced no inventory.
     *
     * @param detail what was wrong, naming the file it was wrong in
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set one scenario file is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("scenario")
                .text("scenario.id")
                .text("scenario.kind")
                .text("scenario.feature")
                .text("scenario.tier")
                .textList("scenario.deployments")
                .text("scenario.runner")
                .optionalTextList("scenario.claims")
                .text("scenario.reason")
                .build();
    }

    /**
     * The closed key set the committed route table is held to when it is read as a feature
     * inventory rather than as a route table.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape routeShape() {
        return PolicyDocument.Shape.named("agent-routes")
                .text("prefix.path")
                .text("prefix.reason")
                .rows("route", row -> row.text("name").text("path").text("method")
                        .text("media_type").answer("body_permitted").text("owning_plan")
                        .text("reason"))
                .rows("alias", row -> row.text("path").text("canonical").text("client_version")
                        .text("pending_correction").text("reason"))
                .build();
    }

    /**
     * Reads every scenario a directory declares.
     *
     * @param scenarios the directory of scenario files
     * @return the inventory, or the one reason a file was refused
     */
    public static Outcome read(Path scenarios) {
        final List<ScenarioRow> rows = new ArrayList<>();
        for (final Path file : filesUnder(scenarios, ".toml")) {
            final PolicyDocument.Outcome outcome = PolicyDocument.load(file, shape());
            if (outcome instanceof final PolicyDocument.Refused refused) {
                return new Refused(file.getFileName() + ": " + refused.failure() + ": "
                        + refused.detail());
            }
            final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
            rows.add(new ScenarioRow(SCENARIOS + "/" + file.getFileName(),
                    document.text("scenario.id"), document.text("scenario.kind"),
                    document.text("scenario.feature"), document.text("scenario.tier"),
                    document.textList("scenario.deployments"), document.text("scenario.runner"),
                    document.textList("scenario.claims")));
        }
        return new Loaded(new ScenarioInventory(rows));
    }

    /**
     * Every declared scenario, in the order the directory holds them.
     *
     * @return the scenarios
     */
    public List<ScenarioRow> scenarios() {
        return java.util.Collections.unmodifiableList(scenarios);
    }

    /**
     * Everything the inventory and the repository disagree about.
     *
     * @param sources everywhere this check reads from
     * @return one finding per disagreement, each naming what was refused
     */
    public PolicyReport against(Sources sources) {
        final List<String> routes = routeNames(sources.routes());
        final List<String> served = servedFeatures(sources);
        final List<String> tiers = tierNames(sources.gate());
        final List<String> deployments = deploymentIdentifiers(sources.deployments());
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(duplicates());
        findings.addAll(shapes(sources, routes, tiers, deployments));
        findings.addAll(classes(sources));
        findings.addAll(uncovered(sources, served, tiers));
        return PolicyReport.of(findings);
    }

    private List<PolicyFinding> duplicates() {
        final Set<String> seen = new LinkedHashSet<>();
        return scenarios.stream()
                .filter(row -> !seen.add(row.identifier()))
                .map(row -> new PolicyFinding(row.file(), PolicyFinding.NO_LINE, "duplicate-scenario",
                        row.identifier() + " is declared more than once"))
                .toList();
    }

    private List<PolicyFinding> shapes(Sources sources, List<String> routes, List<String> tiers,
                                       List<String> deployments) {
        final List<PolicyFinding> findings = new ArrayList<>();
        scenarios.forEach(row -> {
            if (!ROUTE_KIND.equals(row.kind()) && !PROPERTY_KIND.equals(row.kind())
                    && !COMMAND_KIND.equals(row.kind())) {
                findings.add(new PolicyFinding(row.file(), PolicyFinding.NO_LINE, "unknown-kind",
                        row.kind() + " is not a route, a property, or a command"));
            }
            if (COMMAND_KIND.equals(row.kind())
                    && !Files.isRegularFile(registryRow(sources, row.feature()))) {
                findings.add(new PolicyFinding(row.file(), PolicyFinding.NO_LINE, "unknown-command",
                        row.feature() + " names no command this registry declares"));
            }
            if (ROUTE_KIND.equals(row.kind()) && !routes.contains(row.feature())) {
                findings.add(new PolicyFinding(row.file(), PolicyFinding.NO_LINE, "unknown-feature",
                        row.feature() + " is not a route the table declares"));
            }
            if (PROPERTY_KIND.equals(row.kind())
                    && !Files.isRegularFile(policyOf(sources, row.feature()))) {
                findings.add(new PolicyFinding(row.file(), PolicyFinding.NO_LINE, "unknown-property",
                        row.feature() + " names no committed policy"));
            }
            if (!tiers.contains(row.tier())) {
                findings.add(new PolicyFinding(row.file(), PolicyFinding.NO_LINE, "unknown-tier",
                        row.tier() + " is not a tier the gate declares"));
            }
            row.deployments().stream()
                    .filter(declared -> !deployments.contains(declared))
                    .forEach(declared -> findings.add(new PolicyFinding(row.file(),
                            PolicyFinding.NO_LINE, "unknown-deployment",
                            row.identifier() + " runs on " + declared
                                    + ", which the deployment matrix does not declare")));
        });
        return findings;
    }

    private static Path policyOf(Sources sources, String feature) {
        return sources.routes().resolveSibling(feature + ".toml");
    }

    /**
     * Where the registry keeps one command's row, beside the routes table rather than above it.
     *
     * <p>Reached as a sibling of the routes table because a parent is something a path can fail to
     * have, and a check that dereferenced one would fail on a repository laid out as a single
     * directory rather than reporting what it was asked about.</p>
     *
     * @param sources everywhere this check reads from
     * @param feature the command a scenario names
     * @return where its row would be
     */
    private static Path registryRow(Sources sources, String feature) {
        return sources.routes().resolveSibling(REGISTRY_NAME).resolve(feature + ".toml");
    }

    private List<PolicyFinding> classes(Sources sources) {
        final List<PolicyFinding> findings = new ArrayList<>();
        scenarios.stream()
                .filter(row -> !Files.isRegularFile(sources.runners()
                        .resolve(row.runner().replace('.', '/') + ".java")))
                .forEach(row -> findings.add(new PolicyFinding(row.file(), PolicyFinding.NO_LINE,
                        "scenario-with-no-class", row.runner() + " does not exist")));
        filesUnder(sources.runners(), RUNNER_SUFFIX).stream()
                .map(file -> String.valueOf(file.getFileName()))
                .filter(name -> scenarios.stream()
                        .noneMatch(row -> row.runner().endsWith("." + name.replace(".java", ""))))
                .forEach(name -> findings.add(PolicyFinding.inFile(RUNNERS + "/" + name,
                        "class-with-no-scenario", name + " runs no declared scenario")));
        return findings;
    }

    private List<PolicyFinding> uncovered(Sources sources, List<String> served,
                                          List<String> tiers) {
        final String tier = tiers.isEmpty() ? "a" : tiers.getFirst();
        return served.stream()
                .filter(feature -> scenarios.stream()
                        .noneMatch(row -> ROUTE_KIND.equals(row.kind())
                                && row.feature().equals(feature)))
                .map(feature -> PolicyFinding.inFile(relative(sources.routes()),
                        "uncovered-feature", feature + " is served and no scenario covers it;"
                                + " it needs one on tier " + tier))
                .toList();
    }

    private static String relative(Path routes) {
        return ROUTES.endsWith(String.valueOf(routes.getFileName())) ? ROUTES
                : String.valueOf(routes.getFileName());
    }

    /**
     * Every route the committed table declares, in its own order.
     *
     * @param table the route table
     * @return the route names
     * @throws IllegalStateException if the table does not satisfy its own shape, because a feature
     *     inventory nothing can read is one this check would silently pass
     */
    public static List<String> routeNames(Path table) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(table, routeShape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            throw new IllegalStateException(table + " is not a route table: " + refused.detail());
        }
        return ((PolicyDocument.Loaded) outcome).document().rows("route").stream()
                .map(row -> row.text("name"))
                .toList();
    }

    /**
     * Every feature the product actually serves, read out of its own registrations.
     *
     * <p>This is what makes the comparison self-maintaining. A list of served features kept here
     * would be a copy, and a copy is something somebody has to remember to add a line to on the
     * day they add a route — which is the day they are thinking about the route rather than about
     * this file.</p>
     *
     * @param sources everywhere this check reads from
     * @return the names of the routes the sources register, in the table's own order
     */
    public static List<String> servedFeatures(Sources sources) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(sources.routes(), routeShape());
        if (outcome instanceof PolicyDocument.Refused) {
            return List.of();
        }
        final Set<String> registered = new LinkedHashSet<>();
        sources.registrations().stream()
                .flatMap(root -> filesUnder(root, ".java").stream())
                .map(ScenarioInventory::contentOf)
                .forEach(content -> {
                    final Matcher paths = REGISTERED.matcher(content);
                    while (paths.find()) {
                        registered.add(paths.group(1));
                    }
                });
        return ((PolicyDocument.Loaded) outcome).document().rows("route").stream()
                .filter(row -> registered.contains(row.text("path")))
                .map(row -> row.text("name"))
                .toList();
    }

    private static List<String> tierNames(Path gate) {
        final QualityGate.Outcome outcome = QualityGate.readInventory(gate);
        if (outcome instanceof QualityGate.Refused) {
            return List.of();
        }
        return ((QualityGate.Loaded) outcome).gate().tiers().stream()
                .map(QualityGate.TierRow::name)
                .toList();
    }

    private static List<String> deploymentIdentifiers(Path matrix) {
        final DeploymentMatrix.Outcome outcome = DeploymentMatrix.load(matrix);
        if (outcome instanceof DeploymentMatrix.Refused) {
            return List.of();
        }
        return ((DeploymentMatrix.Loaded) outcome).matrix().identifiers();
    }

    private static List<Path> filesUnder(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String contentOf(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * The inventory this repository commits, read from its own scenario directory.
     *
     * @param root the repository root
     * @return the inventory, or the one reason a scenario file was refused
     */
    public static Outcome at(Path root) {
        return read(root.resolve(SCENARIOS));
    }

    /**
     * The one reason a read produced no inventory, where it produced none.
     *
     * @param outcome what the read produced
     * @return the detail, or nothing where an inventory was produced
     */
    public static Optional<String> refusal(Outcome outcome) {
        return outcome instanceof final Refused refused
                ? Optional.of(refused.detail())
                : Optional.empty();
    }
}
