// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * Every artifact this build resolves, chosen once.
 *
 * <p>A dependency nobody chose is a dependency nobody will notice the day it grows, and one
 * resolved at a range or at a snapshot is a build whose result depends on when it ran. So the set
 * is data, every row names an exact version, and the correspondence runs both ways.</p>
 *
 * <p>The rule that decides where this product can be installed sits here too: no product module may
 * resolve anything at compile or runtime scope. Everything a bundle needs at runtime is provided by
 * the deployment, which is what makes the imported-package footprint the whole compatibility
 * statement rather than half of one.</p>
 */
public final class DependencyPolicy {

    private static final String POLICY_FILE = "policy/dependencies.toml";

    private static final String ARTIFACT_ROWS = "artifact";

    /** The scopes a product module may never resolve anything at. */
    private static final List<String> PRODUCT_FORBIDDEN_SCOPES = List.of("compile", "runtime");

    /** The modules this product ships. */
    private static final List<String> PRODUCT_MODULES = List.of("core", "aem");

    /** How a version that is not one version is spelled. */
    private static final List<String> RANGE_MARKERS = List.of("[", "]", "(", ")", ",");

    /** How a version that changes under the same name is spelled. */
    private static final String SNAPSHOT = "SNAPSHOT";

    /** How an executable that reaches the network says so, in its own text. */
    public static final String NETWORK_DECLARATION = "REACHES THE NETWORK";

    private final List<ArtifactRow> artifacts;

    private DependencyPolicy(List<ArtifactRow> artifacts) {
        this.artifacts = artifacts;
    }

    /**
     * One artifact the build resolves.
     *
     * @param group the group identifier
     * @param name the artifact identifier
     * @param version the exact version
     * @param scope the scope it may be resolved at
     * @param modules the modules that may resolve it
     * @param reason why this build has it at all
     */
    public record ArtifactRow(String group, String name, String version, String scope,
                              List<String> modules, String reason) {

        /**
         * Holds a row whose modules nothing can change afterwards.
         */
        public ArtifactRow {
            modules = List.copyOf(modules);
        }

        /**
         * The modules that may resolve this artifact.
         *
         * @return the module names, as a view nothing can change
         */
        @Override
        public List<String> modules() {
            return Collections.unmodifiableList(modules);
        }

        /**
         * The artifact's coordinate without its version or scope.
         *
         * @return the group and artifact identifiers, joined
         */
        public String coordinate() {
            return group + ":" + name;
        }
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(DependencyPolicy policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the dependency policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("dependencies")
                .rows(ARTIFACT_ROWS, row -> row.text("group").text("name").text("version")
                        .text("scope").textList("modules").text("reason"))
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
        final List<ArtifactRow> artifacts = document.rows(ARTIFACT_ROWS).stream()
                .map(row -> new ArtifactRow(row.text("group"), row.text("name"), row.text("version"),
                        row.text("scope"), row.textList("modules"), row.text("reason")))
                .toList();
        final Optional<ArtifactRow> unexplained = artifacts.stream()
                .filter(row -> row.reason().isBlank())
                .findFirst();
        if (unexplained.isPresent()) {
            return new Refused(unexplained.get().coordinate() + " is resolved and records no reason");
        }
        return new Loaded(new DependencyPolicy(artifacts));
    }

    /**
     * Every artifact the policy declares.
     *
     * @return the rows, in the policy's own order
     */
    public List<ArtifactRow> artifacts() {
        return Collections.unmodifiableList(artifacts);
    }

    /**
     * Holds the reactor's resolved dependency sets to the policy in both directions.
     *
     * @param reactor the reactor as the build resolved it
     * @param modules the modules whose resolved sets to read
     * @return one finding per artifact with no row, per row nothing resolves, per version that is
     *     not one exact version, and per product module resolving anything at compile or runtime
     *     scope
     */
    public PolicyReport against(ReactorModel reactor, List<String> modules) {
        final SequencedMap<String, List<ReactorModel.ResolvedArtifact>> resolved =
                new LinkedHashMap<>();
        modules.forEach(module -> resolved.put(module, reactor.resolvedDependencies(module)));
        return againstResolved(resolved);
    }

    /**
     * Holds resolved dependency sets to the policy in both directions.
     *
     * @param resolvedByModule what each module resolved, as the build recorded it
     * @return one finding per artifact with no row, per row nothing resolves, per version that is
     *     not one exact version, and per product module resolving anything at compile or runtime
     *     scope
     */
    public PolicyReport againstResolved(
            SequencedMap<String, List<ReactorModel.ResolvedArtifact>> resolvedByModule) {
        final List<PolicyFinding> findings = new ArrayList<>();
        final List<String> resolvedCoordinates = new ArrayList<>();
        resolvedByModule.forEach((module, artifactsResolved) -> artifactsResolved.forEach(resolved -> {
            resolvedCoordinates.add(resolved.coordinate());
            if (PRODUCT_MODULES.contains(module)
                    && PRODUCT_FORBIDDEN_SCOPES.contains(resolved.scope())) {
                findings.add(PolicyFinding.inFile(module + "/pom.xml", "dependency-scope",
                        resolved.coordinate() + " is resolved by " + module + " at "
                                + resolved.scope() + " scope, so it would be inside the artifact"));
            }
            final Optional<ArtifactRow> row = artifacts.stream()
                    .filter(candidate -> candidate.coordinate().equals(resolved.coordinate()))
                    .findFirst();
            if (row.isEmpty()) {
                findings.add(PolicyFinding.inFile(POLICY_FILE, "dependency-policy",
                        resolved.coordinate() + " is resolved by " + module + " and has no row"));
                return;
            }
            if (!row.get().version().equals(resolved.version())) {
                findings.add(PolicyFinding.inFile(POLICY_FILE, "dependency-policy",
                        resolved.coordinate() + " resolves at " + resolved.version()
                                + " where the policy pins " + row.get().version()));
            }
            if (!row.get().modules().contains(module)) {
                findings.add(PolicyFinding.inFile(module + "/pom.xml", "dependency-policy",
                        resolved.coordinate() + " is resolved by " + module
                                + ", which the policy does not name"));
            }
        }));
        artifacts.stream()
                .filter(row -> !resolvedCoordinates.contains(row.coordinate()))
                .map(row -> PolicyFinding.inFile(POLICY_FILE, "dependency-policy",
                        row.coordinate() + " has a row and nothing resolves it"))
                .forEach(findings::add);
        findings.addAll(versionShapeFindings());
        return PolicyReport.of(findings);
    }

    /**
     * Every executable in the scripts directory that says it reaches the network.
     *
     * <p>The claim the whole arrangement rests on is that nothing the gate runs fetches anything.
     * That is not something a script can assert about itself, so it is decided here: an executable
     * that reaches the network says so in its own text, and the set of those is checked against the
     * one command that is allowed to.</p>
     *
     * @param root the repository root
     * @return the executables that declare they reach the network, in sorted order
     */
    public static List<String> networkReachingScripts(Path root) {
        return RepositoryTree.filesUnder(root.resolve("scripts"), "").stream()
                .filter(script -> RepositoryTree.text(script).contains(NETWORK_DECLARATION))
                .map(script -> String.valueOf(script.getFileName()))
                .sorted()
                .toList();
    }

    /**
     * Whether every declared version is one exact version.
     *
     * @return one finding per range and per snapshot
     */
    public List<PolicyFinding> versionShapeFindings() {
        final List<PolicyFinding> findings = new ArrayList<>();
        artifacts.stream()
                .filter(row -> RANGE_MARKERS.stream().anyMatch(marker -> row.version().contains(marker)))
                .map(row -> PolicyFinding.inFile(POLICY_FILE, "dependency-version",
                        row.coordinate() + " is pinned to the range " + row.version()))
                .forEach(findings::add);
        artifacts.stream()
                .filter(row -> row.version().contains(SNAPSHOT))
                .filter(row -> !row.coordinate().startsWith("rs.slingshot:"))
                .map(row -> PolicyFinding.inFile(POLICY_FILE, "dependency-version",
                        row.coordinate() + " is pinned to " + row.version()
                                + ", which is a name rather than a version"))
                .forEach(findings::add);
        return findings;
    }
}
