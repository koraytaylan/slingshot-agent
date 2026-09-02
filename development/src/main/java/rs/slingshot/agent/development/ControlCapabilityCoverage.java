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
import java.util.Set;

/**
 * Whether the controls the deployments declare and the controls the commands need are the same set.
 *
 * <p>Compared in both directions, because each direction fails differently and both fail quietly. A
 * command needing a capability no row declares is a command that will be refused on every
 * deployment there is — which looks, from the outside, exactly like a command that does not work. A
 * row declaring a capability no command needs is a claim about this product that stopped being true
 * when somebody removed the command, and it will sit there being read as a promise.</p>
 *
 * <p>Every row must say something about every capability, and every absence must carry its own
 * reason. A bare omission reads as "we did not think about it", which is the state this check
 * exists to make impossible: an operator reading the matrix has to be able to tell "this deployment
 * does not permit it, and here is why" from "nobody wrote this row".</p>
 */
public final class ControlCapabilityCoverage {

    private ControlCapabilityCoverage() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "control-capability";

    /** Where the deployments and what each one provides are declared. */
    public static final String MATRIX_FILE = "support/deployments.toml";

    /** Where the closed set of controls is declared, as source this check reads rather than runs. */
    public static final String CAPABILITY_SOURCE =
            "core/src/main/java/rs/slingshot/agent/command/platform/ControlCapability.java";

    /**
     * Where the client's own published table is mirrored.
     *
     * <p>A mapping row is held to the client's table rather than to this repository's registry, on
     * purpose. The mapping is written before the commands are, and it has to be: it is the sentence
     * that says which of them a deployment can refuse, and writing it afterwards means writing it
     * from the handlers, which is where the mistake would already be. What it may not do is name a
     * command nobody on either end has heard of — and the client's table is what says that.</p>
     */
    public static final String CLIENT_TABLE = CommandConformance.CLIENT_TABLE;

    /** Where the commands that pass through the control gate are declared. */
    public static final String COMMAND_CAPABILITIES = "policy/control-capabilities.toml";

    /** A capability a deployment row says nothing about at all. */
    public static final String UNDECLARED = "capability-undeclared-by-row";

    /** A capability a row refuses without saying why. */
    public static final String UNEXPLAINED = "capability-absent-without-a-reason";

    /** A capability no command in this product needs. */
    public static final String UNUSED = "capability-no-command-needs";

    /** A command declaring a capability the closed set does not hold. */
    public static final String UNKNOWN = "capability-no-such-control";

    /** A command in the mapping that neither half of this protocol has heard of. */
    public static final String UNPUBLISHED = "capability-for-an-unpublished-command";

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param matrix the deployment matrix
     * @param capabilities the source declaring the closed set of controls
     * @param mapping the committed file saying which command needs which control
     * @param root the repository root, which is where the client's published table is read from
     */
    public record Sources(Path matrix, Path capabilities, Path mapping, Path root) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(MATRIX_FILE), root.resolve(CAPABILITY_SOURCE),
                    root.resolve(COMMAND_CAPABILITIES), root);
        }

        /**
         * The same sources with the deployment matrix read from somewhere else.
         *
         * @param elsewhere where the matrix sits instead
         * @return the sources
         */
        public Sources withMatrix(Path elsewhere) {
            return new Sources(elsewhere, capabilities, mapping, root);
        }

        /**
         * The same sources with the command mapping read from somewhere else.
         *
         * @param elsewhere where the mapping sits instead
         * @return the sources
         */
        public Sources withMapping(Path elsewhere) {
            return new Sources(matrix, capabilities, elsewhere, root);
        }
    }

    /**
     * The closed key set the committed mapping is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("control-capabilities")
                .rows("control", row -> row.text("command").text("capability").text("reason"))
                .build();
    }

    /**
     * Whether the declared controls and the needed controls are the same set.
     *
     * @param sources everywhere to read from
     * @return one finding per disagreement, each naming what it is about
     */
    public static PolicyReport against(Sources sources) {
        final List<String> capabilities = declaredCapabilities(sources.capabilities());
        final List<PolicyFinding> findings = new ArrayList<>();
        final DeploymentMatrix.Outcome matrix = DeploymentMatrix.load(sources.matrix());
        if (matrix instanceof final DeploymentMatrix.Loaded loaded) {
            findings.addAll(rowFindings(loaded.matrix(), capabilities));
        }
        findings.addAll(mappingFindings(sources, capabilities));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> rowFindings(DeploymentMatrix matrix,
                                                   List<String> capabilities) {
        final List<PolicyFinding> findings = new ArrayList<>();
        matrix.rows().forEach(row -> {
            final Set<String> declared = new LinkedHashSet<>();
            row.controls().forEach(control -> {
                declared.add(control.capability());
                if (control.provision() == DeploymentMatrix.Provision.ABSENT
                        && control.reason().isBlank()) {
                    findings.add(PolicyFinding.inFile(MATRIX_FILE, UNEXPLAINED,
                            row.identifier() + " refuses " + control.capability() + " and says"
                                    + " nothing about why, which reads as nobody having thought"
                                    + " about it"));
                }
            });
            capabilities.stream()
                    .filter(capability -> !declared.contains(capability))
                    .forEach(capability -> findings.add(PolicyFinding.inFile(MATRIX_FILE,
                            UNDECLARED, row.identifier() + " says nothing about " + capability
                                    + ", and an operator cannot tell that from a row nobody"
                                    + " wrote")));
        });
        return findings;
    }

    private static List<PolicyFinding> mappingFindings(Sources sources, List<String> capabilities) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(sources.mapping(), shape());
        if (!(outcome instanceof final PolicyDocument.Loaded loaded)) {
            return List.of(PolicyFinding.inFile(COMMAND_CAPABILITIES, UNKNOWN,
                    "the committed mapping could not be read, so nothing knows which command needs"
                            + " which control"));
        }
        final List<PolicyFinding> findings = new ArrayList<>();
        final Set<String> needed = new LinkedHashSet<>();
        final Set<String> published = CommandConformance.publishedCommands(sources.root());
        loaded.document().rows("control").forEach(row -> {
            needed.add(row.text("capability"));
            if (!capabilities.contains(row.text("capability"))) {
                findings.add(PolicyFinding.inFile(COMMAND_CAPABILITIES, UNKNOWN,
                        row.text("command") + " needs " + row.text("capability")
                                + ", which is not one of the controls there are"));
            }
            if (!published.contains(row.text("command"))) {
                findings.add(PolicyFinding.inFile(COMMAND_CAPABILITIES, UNPUBLISHED,
                        row.text("command") + " is not a command the client publishes, so nothing"
                                + " on either end would ever ask for it"));
            }
        });
        capabilities.stream()
                .filter(capability -> !needed.contains(capability))
                .forEach(capability -> findings.add(PolicyFinding.inFile(CAPABILITY_SOURCE, UNUSED,
                        capability + " is a control no command in this product needs, and a row"
                                + " declaring it reads as a promise about something that is not"
                                + " here")));
        return findings;
    }

    /**
     * The closed set of controls, read from the source that declares it.
     *
     * <p>Read rather than restated, because a list here would be a second copy of the set and the
     * day the two disagree is the day this check passes over the capability somebody added.</p>
     *
     * @param source the enumeration's own source file
     * @return the spellings, in declaration order
     */
    public static List<String> declaredCapabilities(Path source) {
        if (!Files.isRegularFile(source)) {
            return List.of();
        }
        final List<String> spellings = new ArrayList<>();
        read(source).lines()
                .map(String::strip)
                .filter(line -> line.matches("^[A-Z_]+\\(\"[a-z_]+\"\\)[,;]?$"))
                .forEach(line -> spellings.add(
                        line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'))));
        return List.copyOf(spellings);
    }

    /**
     * Which control each command in the mapping needs.
     *
     * @param mapping the committed mapping
     * @return the command names, in the order the file declares them
     */
    public static List<String> mappedCommands(Path mapping) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(mapping, shape());
        return outcome instanceof final PolicyDocument.Loaded loaded
                ? loaded.document().rows("control").stream().map(row -> row.text("command")).toList()
                : List.of();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
