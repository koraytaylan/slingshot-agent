// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.stream.Stream;

/**
 * Whether every command that changes something outside itself is proved by exactly one
 * cross-cutting suite.
 *
 * <p>Each command has its own scenario, which proves that command. What no per-command scenario can
 * catch is the twentieth command quietly behaving unlike the first nineteen — a delete that leaves
 * a stray node behind on a refusal, a write that commits twice, an offer that happens again on a
 * resend. Those are properties of the whole set, and something has to say which suite owns
 * them.</p>
 *
 * <p>Selection is by what a row <em>declares</em> rather than by where its handler lives or by its
 * access. A suite that took every {@code write} row would demand a repository commit from a command
 * that stops a bundle; one that took a package would stop selecting the day somebody moved a class.
 * What a row declares is the one thing that says what kind of change a command makes: a repository
 * mutation declares the repository's own unknown outcome, an admission declares the admission's,
 * and a platform control declares the platform's. Each is a different set of properties, so each
 * needs its own cross-cutting proof, and a row declaring two of them is a command that has not
 * decided what it is.</p>
 *
 * <p>Which categories each cross-cutting suite claims is read from the scenario files rather than
 * written here, so the plan that adds the third kind and its own proof needs no edit to this
 * check.</p>
 */
public final class MutationCoverage {

    private MutationCoverage() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "mutation-coverage";

    /** Where the registry declares one file per command. */
    public static final String REGISTRY_DIRECTORY = "policy/commands";

    /** Where a scenario declares itself, one file per scenario. */
    public static final String SCENARIO_DIRECTORY = "interop/scenarios";

    /** The committed policy that declares which categories say what kind of change a command makes. */
    public static final String POLICY_FILE = "policy/mutation-safety.toml";

    private static final String OUTCOME_ROWS = "outcome";

    /** A row that has not decided what kind of change it makes. */
    public static final String SEVERAL_OUTCOMES = "several-outcome-categories";

    /** A row whose kind of change no cross-cutting suite proves. */
    public static final String UNCLAIMED = "unclaimed-outcome-row";

    /** A row two suites each claim, which is how a command ends up proved by neither. */
    public static final String DOUBLY_CLAIMED = "doubly-claimed-outcome-row";

    /** A suite claiming a category that is not one of the three. */
    public static final String UNKNOWN_CLAIM = "unknown-claimed-category";

    /**
     * The closed key set the committed policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("mutation-safety")
                .rows(OUTCOME_ROWS, row -> row.text("category").text("changes").text("commits")
                        .text("reason"))
                .build();
    }

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param registry the directory one file per command sits in
     * @param scenarios the directory one file per scenario sits in
     * @param policy the committed file declaring which categories say what kind of change is made
     */
    public record Sources(Path registry, Path scenarios, Path policy) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(REGISTRY_DIRECTORY), root.resolve(SCENARIO_DIRECTORY),
                    root.resolve(POLICY_FILE));
        }

        /**
         * The same sources with the registry read from somewhere else.
         *
         * @param elsewhere where the registry sits instead
         * @return the sources
         */
        public Sources withRegistry(Path elsewhere) {
            return new Sources(elsewhere, scenarios, policy);
        }

        /**
         * The same sources with the scenarios read from somewhere else.
         *
         * @param elsewhere where the scenario files sit instead
         * @return the sources
         */
        public Sources withScenarios(Path elsewhere) {
            return new Sources(registry, elsewhere, policy);
        }

        /**
         * The same sources with the policy read from somewhere else.
         *
         * @param elsewhere where the policy sits instead
         * @return the sources
         */
        public Sources withPolicy(Path elsewhere) {
            return new Sources(registry, scenarios, elsewhere);
        }
    }

    /**
     * The categories the committed policy declares, which is the whole set there is.
     *
     * @param policy the committed policy file
     * @return the categories, in the order the policy declares them
     */
    public static List<String> outcomeCategories(Path policy) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(policy, shape());
        return outcome instanceof final PolicyDocument.Loaded loaded
                ? loaded.document().rows(OUTCOME_ROWS).stream()
                        .map(row -> row.text("category")).toList()
                : List.of();
    }

    /**
     * Whether the registry partitions cleanly across the cross-cutting suites.
     *
     * @param sources everywhere to read from
     * @return one finding per row or suite that breaks the partition, each naming what it is about
     */
    public static PolicyReport against(Sources sources) {
        final List<String> categories = outcomeCategories(sources.policy());
        if (categories.isEmpty()) {
            return PolicyReport.of(List.of(PolicyFinding.inFile(POLICY_FILE, UNKNOWN_CLAIM,
                    "the committed policy declares no outcome categories, so nothing here knows"
                            + " what kind of change any command makes")));
        }
        final SequencedMap<String, List<String>> claimedBy = claims(sources.scenarios());
        final List<PolicyFinding> findings = new ArrayList<>(unknownClaims(claimedBy, categories));
        declaredOutcomes(sources.registry(), categories).forEach((command, declared) -> {
            final String file = REGISTRY_DIRECTORY + "/" + command + ".toml";
            if (declared.size() > 1) {
                findings.add(PolicyFinding.inFile(file, SEVERAL_OUTCOMES, command + " declares "
                        + declared + ", and a command that changes a repository and a machine this"
                        + " agent cannot observe is two commands"));
                return;
            }
            if (declared.isEmpty()) {
                return;
            }
            findings.addAll(partitioned(file, command, declared.getFirst(), claimedBy));
        });
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> partitioned(String file, String command, String outcome,
                                                   Map<String, List<String>> claimedBy) {
        final List<String> suites = claimedBy.getOrDefault(outcome, List.of());
        if (suites.isEmpty()) {
            return List.of(PolicyFinding.inFile(file, UNCLAIMED, command + " declares " + outcome
                    + " and no cross-cutting scenario claims it, so nothing proves it behaves like"
                    + " the rest of its kind"));
        }
        return suites.size() > 1
                ? List.of(PolicyFinding.inFile(file, DOUBLY_CLAIMED, command + " declares "
                        + outcome + " and " + suites + " each claim it, which is how a command ends"
                        + " up proved by neither because both assumed the other had it"))
                : List.of();
    }

    private static List<PolicyFinding> unknownClaims(Map<String, List<String>> claimedBy,
                                                     List<String> categories) {
        return claimedBy.entrySet().stream()
                .filter(claimed -> !categories.contains(claimed.getKey()))
                .map(claimed -> PolicyFinding.inFile(SCENARIO_DIRECTORY, UNKNOWN_CLAIM,
                        claimed.getValue() + " claims " + claimed.getKey() + ", which is not one of"
                                + " the categories that says what kind of change a command makes"))
                .toList();
    }

    /**
     * Which cross-cutting suites claim each outcome category.
     *
     * @param scenarios the directory of scenario files
     * @return the suites by category, in the order the directory holds them
     */
    public static SequencedMap<String, List<String>> claims(Path scenarios) {
        final SequencedMap<String, List<String>> claimed = new LinkedHashMap<>();
        final ScenarioInventory.Outcome outcome = ScenarioInventory.read(scenarios);
        if (!(outcome instanceof final ScenarioInventory.Loaded loaded)) {
            return claimed;
        }
        loaded.inventory().scenarios().forEach(scenario -> scenario.claims().forEach(category ->
                claimed.computeIfAbsent(category, held -> new ArrayList<>())
                        .add(scenario.identifier())));
        return claimed;
    }

    /**
     * Which of the three outcome categories each registry row declares.
     *
     * @param registry the directory of registry rows
     * @param categories the categories the committed policy declares
     * @return the categories by command, in the order the directory holds them
     */
    public static SequencedMap<String, List<String>> declaredOutcomes(Path registry,
                                                                      List<String> categories) {
        final SequencedMap<String, List<String>> declared = new LinkedHashMap<>();
        rowsUnder(registry).forEach(row -> {
            final String text = read(row);
            final String name = String.valueOf(row.getFileName());
            declared.put(name.substring(0, name.length() - ".toml".length()),
                    categories.stream().filter(text::contains).toList());
        });
        return declared;
    }

    private static List<Path> rowsUnder(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> held = Files.list(directory)) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
