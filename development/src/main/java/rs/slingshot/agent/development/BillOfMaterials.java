// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a release contains, and what it binds to at run time without containing.
 *
 * <p>The two are different claims and both are worth publishing. What the container package
 * contains is checkable by opening it — and so the bill is generated from the artifacts rather than
 * maintained beside them, because a list maintained beside something is a list that stops matching
 * it. What the bundles bind to at run time is not in any archive at all: this product embeds
 * nothing, and an imported package is a dependency on somebody else's platform providing it.</p>
 *
 * <p>That second half is the one worth stating loudly. A components list that omitted it would say
 * this release depends on nothing, which is true about the archive and false about the software.
 * </p>
 */
public final class BillOfMaterials {

    /** Where the artifacts a release consists of are declared. */
    public static final String INVENTORY = "support/release-artifacts.toml";

    /** Where every dependency this build resolves is declared. */
    public static final String DEPENDENCIES = "policy/dependencies.toml";

    /** The rule a component the archives do not contain is reported under. */
    public static final String A_COMPONENT_NOT_CONTAINED = "a-component-the-archives-do-not-hold";

    /** The rule an archived artifact with no component row is reported under. */
    public static final String AN_ARTIFACT_WITH_NO_COMPONENT = "an-artifact-with-no-component";

    /** The rule a declared relationship that is not a provided dependency is reported under. */
    public static final String A_RELATIONSHIP_NOBODY_DECLARED = "a-relationship-nobody-declared";

    /** What kind the components list itself is, which is in the release like everything else. */
    public static final String COMPONENTS = "components";

    private BillOfMaterials() {
    }

    /**
     * Every artifact a release contains, which is what the bill's contained set has to equal.
     *
     * <p>The components list itself is not contained by the release it describes; it is beside it.
     * Including it would be a list that lists itself, which is one entry nobody can check.</p>
     *
     * @param root the repository root
     * @return the artifact names, in the inventory's own order
     */
    public static List<String> contained(Path root) {
        return ReleaseArtifacts.read(root) instanceof final ReleaseArtifacts.Loaded loaded
                ? loaded.artifacts().artifacts().stream()
                        .filter(artifact -> !COMPONENTS.equals(artifact.kind()))
                        .map(ReleaseArtifacts.Artifact::name)
                        .toList()
                : List.of();
    }

    /**
     * Everything this build binds to at run time and embeds none of.
     *
     * <p>Provided scope, because that is exactly the relationship: compiled against, resolved from
     * somebody else's platform, carried in no archive here.</p>
     *
     * @param root the repository root
     * @return the coordinates, in the policy's own order
     */
    public static List<String> boundAtRuntime(Path root) {
        final List<String> bound = new ArrayList<>();
        String group = "";
        String name = "";
        for (final String line : RepositoryTree.text(root.resolve(DEPENDENCIES)).lines().toList()) {
            final String stripped = line.strip();
            if (stripped.startsWith("group = ")) {
                group = quoted(stripped);
            } else if (stripped.startsWith("name = ")) {
                name = quoted(stripped);
            } else if (stripped.startsWith("scope = ") && "provided".equals(quoted(stripped))) {
                bound.add(group + ":" + name);
            }
        }
        return List.copyOf(bound);
    }

    /**
     * Whether a bill says exactly what the release contains and binds to.
     *
     * @param root the repository root
     * @param componentNames what the bill says the release contains
     * @param relationships what the bill says it binds to
     * @return one finding per disagreement, in both directions
     */
    public static PolicyReport against(Path root, List<String> componentNames,
                                       List<String> relationships) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<String> contained = contained(root);
        componentNames.stream()
                .filter(component -> !contained.contains(component))
                .map(component -> PolicyFinding.inFile(INVENTORY, A_COMPONENT_NOT_CONTAINED,
                        component + " is listed and the release does not contain it"))
                .forEach(findings::add);
        contained.stream()
                .filter(artifact -> !componentNames.contains(artifact))
                .map(artifact -> PolicyFinding.inFile(INVENTORY, AN_ARTIFACT_WITH_NO_COMPONENT,
                        artifact + " is in the release and the list does not mention it"))
                .forEach(findings::add);
        final List<String> bound = boundAtRuntime(root);
        relationships.stream()
                .filter(relationship -> !bound.contains(relationship))
                .map(relationship -> PolicyFinding.inFile(DEPENDENCIES,
                        A_RELATIONSHIP_NOBODY_DECLARED, relationship + " is declared as something"
                                + " this binds to and no provided dependency says so"))
                .forEach(findings::add);
        return PolicyReport.of(findings);
    }

    /**
     * What a bill generated from this repository would say.
     *
     * <p>Generated rather than maintained, which is the whole point: a list maintained beside
     * something is a list that stops matching it, and the first thing it stops matching is the
     * artifact somebody added in a hurry.</p>
     *
     * @param root the repository root
     * @return the bill, in a form a reader and a tool can both take
     */
    public static String generated(Path root) {
        return "{\"contains\":[" + quotedList(contained(root))
                + "],\"bindsAtRuntime\":[" + quotedList(boundAtRuntime(root)) + "]}";
    }

    private static String quotedList(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String quoted(String line) {
        return line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
    }
}
