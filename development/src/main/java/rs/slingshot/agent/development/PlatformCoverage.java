// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Whether every command that changes the platform is guarded by a capability and claimed by a suite.
 *
 * <p>Three things have to hold together and each of them fails silently on its own. Every row
 * declaring the platform control's own unknown outcome has to be one some deployment can refuse —
 * a control nothing gates is a control that runs on the environment that will discard it. Every
 * such command has to appear in the capability mapping, or the gate it passes through is a gate
 * with no rule behind it. And the cross-cutting suite has to claim the category, or nothing proves
 * what these thirty have in common.</p>
 *
 * <p>Enumerated from the registry rather than from a list here, so the plan that adds a thirty-first
 * platform control is covered by this check without editing it — which is the only way a coverage
 * check stays true.</p>
 */
public final class PlatformCoverage {

    private PlatformCoverage() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "platform-coverage";

    /** Where the registry declares one file per command. */
    public static final String REGISTRY_DIRECTORY = "policy/commands";

    /** The category a command that changes the platform declares. */
    public static final String PLATFORM_OUTCOME = "platform_control_outcome_unknown";

    /** A platform control the capability mapping does not gate. */
    public static final String UNGATED = "platform-control-with-no-capability";

    /** A capability mapping row for a command that changes nothing. */
    public static final String OVERGATED = "capability-for-a-command-that-changes-nothing";

    /** The category no cross-cutting suite claims. */
    public static final String UNCLAIMED = "platform-category-no-scenario-claims";

    /** A platform control that also declares the repository mutation's own unknown outcome. */
    public static final String BOTH_OUTCOMES = "platform-control-that-also-commits";

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param registry the directory one file per command sits in
     * @param mapping the committed file saying which command needs which control
     * @param scenarios the directory one scenario file per scenario sits in
     */
    public record Sources(Path registry, Path mapping, Path scenarios) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(REGISTRY_DIRECTORY),
                    root.resolve(ControlCapabilityCoverage.COMMAND_CAPABILITIES),
                    root.resolve(MutationCoverage.SCENARIO_DIRECTORY));
        }

        /**
         * The same sources with the registry read from somewhere else.
         *
         * @param elsewhere where the registry sits instead
         * @return the sources
         */
        public Sources withRegistry(Path elsewhere) {
            return new Sources(elsewhere, mapping, scenarios);
        }

        /**
         * The same sources with the capability mapping read from somewhere else.
         *
         * @param elsewhere where the mapping sits instead
         * @return the sources
         */
        public Sources withMapping(Path elsewhere) {
            return new Sources(registry, elsewhere, scenarios);
        }

        /**
         * The same sources with the scenarios read from somewhere else.
         *
         * @param elsewhere where the scenario files sit instead
         * @return the sources
         */
        public Sources withScenarios(Path elsewhere) {
            return new Sources(registry, mapping, elsewhere);
        }
    }

    /**
     * Whether the controls, the capabilities, and the claim all agree.
     *
     * @param sources everywhere to read from
     * @return one finding per disagreement, each naming what it is about
     */
    public static PolicyReport against(Sources sources) {
        final List<String> controls = controlsIn(sources.registry());
        final List<String> gated = ControlCapabilityCoverage.mappedCommands(sources.mapping());
        final List<PolicyFinding> findings = new ArrayList<>();
        controls.stream()
                .filter(command -> !gated.contains(command))
                .forEach(command -> findings.add(PolicyFinding.inFile(
                        REGISTRY_DIRECTORY + "/" + command + ".toml", UNGATED, command
                                + " changes the platform and no deployment can refuse it, so it"
                                + " would run on the environment that discards the change")));
        // A gated command need not be a platform control. Users and groups live in the caller's
        // own repository, so the seven that change them declare the repository mutation's own
        // unknown outcome and still pass a gate a deployment may refuse. What a gate may never sit
        // in front of is a command that changes nothing at all.
        final List<String> changing = changingIn(sources.registry());
        gated.stream()
                .filter(command -> !changing.contains(command))
                .forEach(command -> findings.add(PolicyFinding.inFile(
                        ControlCapabilityCoverage.COMMAND_CAPABILITIES, OVERGATED, command
                                + " is gated by a capability and declares no outcome anybody could"
                                + " be unsure about, so it is gated for something it does not do")));
        findings.addAll(bothFindings(sources.registry()));
        findings.addAll(claimFindings(sources, controls));
        return PolicyReport.of(findings);
    }

    /**
     * Every command whose registry row says it changes something outside itself.
     *
     * @param registry the registry directory
     * @return the command names, in ascending wire-name order
     */
    public static List<String> changingIn(Path registry) {
        return rowFiles(registry).stream()
                .filter(file -> read(file).contains("outcome_unknown"))
                .map(PlatformCoverage::commandOf)
                .toList();
    }

    private static List<PolicyFinding> bothFindings(Path registry) {
        return rowFiles(registry).stream()
                .filter(file -> read(file).contains(PLATFORM_OUTCOME)
                        && read(file).contains("mutation_outcome_unknown"))
                .map(file -> PolicyFinding.inFile(
                        REGISTRY_DIRECTORY + "/" + commandOf(file) + ".toml", BOTH_OUTCOMES,
                        commandOf(file) + " declares that it changes the platform and that it"
                                + " commits to the caller's repository, and a command that does"
                                + " both is two commands"))
                .toList();
    }

    private static List<PolicyFinding> claimFindings(Sources sources, List<String> controls) {
        if (controls.isEmpty()) {
            return List.of();
        }
        return MutationCoverage.claims(sources.scenarios()).containsKey(PLATFORM_OUTCOME)
                ? List.of()
                : List.of(PolicyFinding.inFile(MutationCoverage.SCENARIO_DIRECTORY, UNCLAIMED,
                        controls.size() + " commands change the platform and no cross-cutting"
                                + " scenario claims " + PLATFORM_OUTCOME + ", so nothing proves"
                                + " what they have in common"));
    }

    /**
     * Every command whose registry row says it changes the platform.
     *
     * @param registry the registry directory
     * @return the command names, in ascending wire-name order
     */
    public static List<String> controlsIn(Path registry) {
        return rowFiles(registry).stream()
                .filter(file -> read(file).contains(PLATFORM_OUTCOME))
                .map(PlatformCoverage::commandOf)
                .toList();
    }

    private static List<Path> rowFiles(Path registry) {
        if (!Files.isDirectory(registry)) {
            return List.of();
        }
        try (Stream<Path> held = Files.list(registry)) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String commandOf(Path file) {
        final String name = String.valueOf(file.getFileName());
        return name.substring(0, name.length() - ".toml".length());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
