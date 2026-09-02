// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the registry enforces, decided here rather than discovered from a rejection.
 *
 * <p>A release refused remotely is a release somebody has to work out the reason for from whatever
 * the portal said. A release refused here names all of it at once, offline, before anything was
 * built — which is the difference between a prerequisite and a surprise.</p>
 *
 * <p>The prerequisites are data rather than lore in a script, so somebody can compare the list with
 * the registry's own published requirements and see whether it is still the list. A script that
 * happened to check six things is a script nobody can audit against anything.</p>
 */
public final class CentralPrerequisites {

    /** Where the prerequisites are declared. */
    public static final String POLICY_FILE = "policy/central-prerequisites.toml";

    /** Where the project's own metadata is declared once and inherited. */
    public static final String AGGREGATOR = "pom.xml";

    /** The rule a prerequisite the model does not satisfy is reported under. */
    public static final String NOT_SATISFIED = "prerequisite-not-satisfied";

    /** The rule a version that means something different tomorrow is reported under. */
    public static final String A_SNAPSHOT_VERSION = "a-snapshot-version";

    /** What a version that is not a release ends with. */
    private static final String SNAPSHOT = "-SNAPSHOT";

    private final List<Prerequisite> prerequisites;

    private CentralPrerequisites(List<Prerequisite> prerequisites) {
        this.prerequisites = prerequisites;
    }

    /**
     * One thing the registry enforces.
     *
     * @param identifier what it is called here
     * @param element what part of the model or the artifact set satisfies it
     */
    public record Prerequisite(String identifier, String element) {
    }

    /** The result of reading the prerequisites: the list, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A list that satisfied its shape completely.
     *
     * @param prerequisites the loaded list
     */
    public record Loaded(CentralPrerequisites prerequisites) implements Outcome {
    }

    /**
     * A read that produced none.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the list is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("central-prerequisites")
                .text("registry.name")
                .text("registry.reason")
                .rows("prerequisite", row -> row.text("id").text("element").text("reason"))
                .build();
    }

    /**
     * Reads the list this repository commits.
     *
     * @param root the repository root
     * @return the prerequisites, or the one reason there are none
     */
    public static Outcome read(Path root) {
        return readFile(root.resolve(POLICY_FILE));
    }

    /**
     * Reads one list wherever it sits, so a fixture can replace it and nothing else.
     *
     * @param file the list
     * @return the prerequisites, or the one reason there are none
     */
    public static Outcome readFile(Path file) {
        final PolicyDocument.Outcome outcome = PolicyDocument.load(file, shape());
        if (outcome instanceof final PolicyDocument.Refused refused) {
            return new Refused(refused.failure() + ": " + refused.detail());
        }
        return new Loaded(new CentralPrerequisites(
                ((PolicyDocument.Loaded) outcome).document().rows("prerequisite").stream()
                        .map(row -> new Prerequisite(row.text("id"), row.text("element")))
                        .toList()));
    }

    /**
     * Every prerequisite this list holds, in its own order.
     *
     * @return the prerequisites
     */
    public List<Prerequisite> prerequisites() {
        return java.util.Collections.unmodifiableList(prerequisites);
    }

    /**
     * Every prerequisite the repository does not satisfy, all at once.
     *
     * @param root the repository root
     * @return one finding per unsatisfied prerequisite
     */
    public PolicyReport against(Path root) {
        final String model = RepositoryTree.text(root.resolve(AGGREGATOR));
        final List<PolicyFinding> findings = new ArrayList<>();
        prerequisites.stream()
                .filter(prerequisite -> !satisfied(prerequisite, model, root))
                .map(prerequisite -> PolicyFinding.inFile(AGGREGATOR, NOT_SATISFIED,
                        prerequisite.identifier() + " needs " + prerequisite.element()
                                + " and the model does not declare it"))
                .forEach(findings::add);
        if (versionOf(model).endsWith(SNAPSHOT)) {
            findings.add(PolicyFinding.inFile(AGGREGATOR, A_SNAPSHOT_VERSION,
                    versionOf(model) + " means something different tomorrow, and a registry that"
                            + " accepted one would be accepting a promise rather than an"
                            + " artifact"));
        }
        return PolicyReport.of(findings);
    }

    /**
     * Whether the repository satisfies one prerequisite.
     *
     * @param prerequisite the prerequisite
     * @param model the aggregator's own text
     * @param root the repository root
     * @return whether it does
     */
    private static boolean satisfied(Prerequisite prerequisite, String model, Path root) {
        return switch (prerequisite.element()) {
            case "project.name" -> model.contains("<name>");
            case "project.description" -> model.contains("<description>");
            case "project.url" -> model.contains("<url>");
            case "project.licenses" -> model.contains("<licenses>");
            case "project.developers" -> model.contains("<developers>");
            case "project.scm" -> model.contains("<scm>");
            case "project.version" -> true;
            case "artifact.sources" -> declaredArtifacts(root).contains("sources");
            case "artifact.javadoc" -> declaredArtifacts(root).contains("documentation");
            case "artifact.signature" -> signingIsDeclared(root);
            default -> false;
        };
    }

    /**
     * Every kind of artifact the release inventory declares.
     *
     * @param root the repository root
     * @return the kinds
     */
    private static List<String> declaredArtifacts(Path root) {
        return ReleaseArtifacts.read(root) instanceof final ReleaseArtifacts.Loaded loaded
                ? loaded.artifacts().artifacts().stream()
                        .map(ReleaseArtifacts.Artifact::kind)
                        .distinct()
                        .toList()
                : List.of();
    }

    /**
     * Whether an identity artifacts would be signed by is declared at all.
     *
     * <p>Not whether anything was signed. The key is never in this repository, so what is checkable
     * here is that somebody has said who signs — and a release with nobody named would be one whose
     * signature nothing could be checked against.</p>
     *
     * @param root the repository root
     * @return whether one is declared
     */
    private static boolean signingIsDeclared(Path root) {
        return RepositoryTree.text(root.resolve(PublicationAuthority.METADATA)).lines()
                .map(String::strip)
                .anyMatch(line -> line.startsWith("identity = \"")
                        && !"identity = \"\"".equals(line));
    }

    private static String versionOf(String model) {
        return model.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("<version>"))
                .map(line -> line.substring("<version>".length(), line.indexOf("</version>")))
                .findFirst()
                .orElse("");
    }
}
