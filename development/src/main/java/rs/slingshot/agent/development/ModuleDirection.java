// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.model.Dependency;

/**
 * The module diagram, checked against the reactor the build actually resolved.
 *
 * <p>Reading the resolved model rather than the declared text is the whole point: an edge a module
 * inherits from the aggregator is an edge that module has, and a check reading manifests would not
 * see it. The direction is one-way and the scope is part of it — a tooling module may read a
 * product module at test scope and at no other, and no product module may reach a tooling one in
 * any direction at any scope.</p>
 *
 * <p>The forbidden-namespace rule is the one the public interoperability tier depends on. It is
 * decided over the artifacts on the resolved compile classpath, opened and read, rather than over
 * their coordinates, because an artifact's name says nothing about the packages inside it.</p>
 */
public final class ModuleDirection {

    private static final String MODULE_ROWS = "module";

    private static final String EDGE_ROWS = "edge";

    private static final String NAMESPACE_ROWS = "forbidden_namespace";

    private static final String POLICY_FILE = "policy/module-direction.toml";

    private final List<ModuleRow> modules;
    private final List<EdgeRow> edges;
    private final List<NamespaceRow> namespaces;

    private ModuleDirection(List<ModuleRow> modules, List<EdgeRow> edges,
                            List<NamespaceRow> namespaces) {
        this.modules = modules;
        this.edges = edges;
        this.namespaces = namespaces;
    }

    /**
     * One module, and what kind of thing it is.
     *
     * @param name the module's directory name
     * @param artifact the artifact identifier it produces
     * @param kind {@code bundle}, {@code content-package}, or {@code tooling}
     */
    public record ModuleRow(String name, String artifact, String kind) {
    }

    /**
     * One edge a module is allowed to have.
     *
     * @param from the module that depends
     * @param to the module it depends on
     * @param scope the one scope this edge may use
     * @param reason why the edge exists, in the words of somebody who chose it
     */
    public record EdgeRow(String from, String to, String scope, String reason) {
    }

    /**
     * A package namespace one module may not have on its resolved compile classpath.
     *
     * @param module the module the rule applies to
     * @param namespace the package prefix no artifact on that classpath may carry
     * @param reason why the namespace is refused there
     */
    public record NamespaceRow(String module, String namespace, String reason) {
    }

    /**
     * The closed key set the module-direction policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("module-direction")
                .rows(MODULE_ROWS, row -> row.text("name").text("artifact").text("kind"))
                .rows(EDGE_ROWS, row -> row.text("from").text("to").text("scope").text("reason"))
                .rows(NAMESPACE_ROWS, row -> row.text("module").text("namespace").text("reason"))
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
            return new Refused(policy + ": " + refused.failure() + ": " + refused.detail());
        }
        final PolicyDocument document = ((PolicyDocument.Loaded) outcome).document();
        return new Loaded(new ModuleDirection(
                document.rows(MODULE_ROWS).stream()
                        .map(row -> new ModuleRow(row.text("name"), row.text("artifact"), row.text("kind")))
                        .toList(),
                document.rows(EDGE_ROWS).stream()
                        .map(row -> new EdgeRow(row.text("from"), row.text("to"), row.text("scope"),
                                row.text("reason")))
                        .toList(),
                document.rows(NAMESPACE_ROWS).stream()
                        .map(row -> new NamespaceRow(row.text("module"), row.text("namespace"),
                                row.text("reason")))
                        .toList()));
    }

    /** The result of reading a policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(ModuleDirection policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document, named so that somebody can fix it
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * Every module the policy names.
     *
     * @return the module rows, in the policy's own order
     */
    public List<ModuleRow> modules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * Every edge the policy allows.
     *
     * @return the edge rows, in the policy's own order
     */
    public List<EdgeRow> edges() {
        return Collections.unmodifiableList(edges);
    }

    /**
     * Holds a reactor to the policy in both directions.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per module missing from the policy, per policy row naming a module that
     *     does not exist, per resolved edge the policy does not allow, and per allowed edge
     *     resolved at a scope the policy does not give it
     */
    public PolicyReport against(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        findings.addAll(membershipFindings(reactor));
        findings.addAll(edgeFindings(reactor));
        return PolicyReport.of(findings);
    }

    /**
     * Holds every artifact on a module's resolved compile classpath to the namespaces it may carry.
     *
     * @param reactor the reactor as the build resolved it
     * @return one finding per artifact carrying a package from a namespace that module refuses
     */
    public PolicyReport namespacesOnClasspath(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        namespaces.forEach(rule -> reactor.compileClasspath(rule.module()).stream()
                .filter(Files::isRegularFile)
                .filter(artifact -> carriesNamespace(artifact, rule.namespace()))
                .map(artifact -> PolicyFinding.inFile(rule.module() + "/pom.xml", "forbidden-namespace",
                        artifact.getFileName() + " carries " + rule.namespace()))
                .forEach(findings::add));
        return PolicyReport.of(findings);
    }

    private List<PolicyFinding> membershipFindings(ReactorModel reactor) {
        final Set<String> declared = modules.stream()
                .map(ModuleRow::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final List<PolicyFinding> findings = new ArrayList<>();
        reactor.modules().stream()
                .filter(module -> !declared.contains(module))
                .map(module -> PolicyFinding.inFile(POLICY_FILE, "module-membership",
                        module + " is in the reactor and in no policy row"))
                .forEach(findings::add);
        modules.stream()
                .map(ModuleRow::name)
                .filter(module -> !reactor.modules().contains(module))
                .map(module -> PolicyFinding.inFile(POLICY_FILE, "module-membership",
                        module + " is in a policy row and in no reactor"))
                .forEach(findings::add);
        return findings;
    }

    private List<PolicyFinding> edgeFindings(ReactorModel reactor) {
        final List<PolicyFinding> findings = new ArrayList<>();
        reactor.modules().forEach(module -> reactor.dependencies(module).stream()
                .flatMap(dependency -> moduleProducing(dependency).stream()
                        .map(target -> edgeFinding(module, target, dependency))
                        .flatMap(Optional::stream))
                .forEach(findings::add));
        edges.stream()
                .filter(edge -> !resolves(reactor, edge))
                .map(edge -> PolicyFinding.inFile(POLICY_FILE, "module-direction",
                        edge.from() + " to " + edge.to() + " is allowed and nothing resolves it"))
                .forEach(findings::add);
        return findings;
    }

    private Optional<PolicyFinding> edgeFinding(String from, String to, Dependency dependency) {
        final String file = from + "/pom.xml";
        if (from.equals(to)) {
            return Optional.of(PolicyFinding.inFile(file, "module-self-dependency",
                    from + " depends on itself"));
        }
        final Optional<EdgeRow> allowed = edges.stream()
                .filter(edge -> edge.from().equals(from) && edge.to().equals(to))
                .findFirst();
        if (allowed.isEmpty()) {
            return Optional.of(PolicyFinding.inFile(file, "module-direction",
                    from + " depends on " + to + " and no policy row allows it"));
        }
        final String scope = dependency.getScope() == null ? "compile" : dependency.getScope();
        if (!allowed.get().scope().equals(scope)) {
            return Optional.of(PolicyFinding.inFile(file, "module-direction-scope",
                    from + " depends on " + to + " at " + scope + " scope where the policy allows "
                            + allowed.get().scope()));
        }
        return Optional.empty();
    }

    private boolean resolves(ReactorModel reactor, EdgeRow edge) {
        return reactor.modules().contains(edge.from())
                && reactor.dependencies(edge.from()).stream()
                        .flatMap(dependency -> moduleProducing(dependency).stream())
                        .anyMatch(target -> target.equals(edge.to()));
    }

    private Optional<String> moduleProducing(Dependency dependency) {
        return modules.stream()
                .filter(module -> module.artifact().equals(dependency.getArtifactId()))
                .map(ModuleRow::name)
                .findFirst();
    }

    private static boolean carriesNamespace(Path artifact, String namespace) {
        final String prefix = namespace.replace('.', '/') + "/";
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            try (Stream<? extends ZipEntry> entries = archive.stream()) {
                return entries.anyMatch(entry -> entry.getName().startsWith(prefix));
            }
        } catch (final IOException notAnArchive) {
            throw new UncheckedIOException(notAnArchive);
        }
    }
}
