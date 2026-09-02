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
 * Every package a bundle imports, held to the set somebody chose.
 *
 * <p>The check reads the produced manifest rather than the build configuration, because a bundle's
 * compatibility is a property of the artifact and not of the intention. It compares in both
 * directions: an import with no row is a footprint that grew without anybody noticing, and a row
 * nothing imports is a claim about a dependency that is no longer there.</p>
 *
 * <p>Beside that sits the rule that nothing arrives inside either artifact. A private package this
 * repository does not own, an embedding instruction, an included jar, or a class path naming
 * anything but the bundle itself are each refused from the manifest, which is the only place the
 * answer is not a claim.</p>
 */
public final class ImportedPackages {

    private static final String POLICY_FILE = "policy/imported-packages.toml";

    private static final String PACKAGE_ROWS = "package";

    private static final String RUNTIME_ROWS = "runtime_namespace";

    private static final String OWN_ROWS = "own_namespace";

    private static final String IMPORT_HEADER = "Import-Package";

    private static final String PRIVATE_HEADER = "Private-Package";

    private static final String CLASS_PATH_HEADER = "Bundle-ClassPath";

    private static final String INCLUDE_RESOURCE_HEADER = "Include-Resource";

    private static final String EMBED_HEADER = "Embed-Dependency";

    /** The only class path a bundle that embeds nothing has. */
    private static final String OWN_CLASS_PATH = ".";

    /** The attribute an import clause states its accepted range in. */
    private static final String VERSION_ATTRIBUTE = "version";

    private final List<PackageRow> packages;
    private final List<NamespaceRow> runtimeNamespaces;
    private final List<NamespaceRow> ownNamespaces;

    private ImportedPackages(List<PackageRow> packages, List<NamespaceRow> runtimeNamespaces,
                             List<NamespaceRow> ownNamespaces) {
        this.packages = packages;
        this.runtimeNamespaces = runtimeNamespaces;
        this.ownNamespaces = ownNamespaces;
    }

    /**
     * One imported package, chosen once.
     *
     * @param name the package name
     * @param range the exact version range the manifest must accept for it
     * @param bundle the module whose bundle imports it
     * @param providedBy the deployment rows that provide it, which must be all of them
     * @param reason why this bundle imports it at all
     */
    public record PackageRow(String name, String range, String bundle, List<String> providedBy,
                             String reason) {

        /**
         * Holds a row whose providing deployments nothing can change afterwards.
         */
        public PackageRow {
            providedBy = List.copyOf(providedBy);
        }

        /**
         * The deployments that provide this package.
         *
         * @return the deployment identifiers, as a view nothing can change
         */
        @Override
        public List<String> providedBy() {
            return Collections.unmodifiableList(providedBy);
        }
    }

    /**
     * A package namespace that needs no row of its own.
     *
     * @param namespace the package prefix
     * @param reason why no row is needed
     */
    public record NamespaceRow(String namespace, String reason) {
    }

    /** The result of reading the policy: the policy, or the one reason there is none. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A policy document that satisfied its shape completely.
     *
     * @param policy the loaded policy
     */
    public record Loaded(ImportedPackages policy) implements Outcome {
    }

    /**
     * A read that produced no policy.
     *
     * @param detail what was wrong with the document, named so that somebody can fix it
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * The closed key set the imported-package policy is held to.
     *
     * @return the shape
     */
    public static PolicyDocument.Shape shape() {
        return PolicyDocument.Shape.named("imported-packages")
                .rows(PACKAGE_ROWS, row -> row.text("name").text("range").text("bundle")
                        .textList("provided_by").text("reason"))
                .rows(RUNTIME_ROWS, row -> row.text("namespace").text("reason"))
                .rows(OWN_ROWS, row -> row.text("namespace").text("reason"))
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
        return new Loaded(new ImportedPackages(
                document.rows(PACKAGE_ROWS).stream()
                        .map(row -> new PackageRow(row.text("name"), row.text("range"),
                                row.text("bundle"), row.textList("provided_by"), row.text("reason")))
                        .toList(),
                namespaceRows(document, RUNTIME_ROWS),
                namespaceRows(document, OWN_ROWS)));
    }

    private static List<NamespaceRow> namespaceRows(PolicyDocument document, String key) {
        return document.rows(key).stream()
                .map(row -> new NamespaceRow(row.text("namespace"), row.text("reason")))
                .toList();
    }

    /**
     * Every package row the policy declares.
     *
     * @return the rows, in the policy's own order
     */
    public List<PackageRow> packages() {
        return Collections.unmodifiableList(packages);
    }

    /**
     * Holds one built bundle's manifest to the policy in both directions.
     *
     * @param bundle the module whose bundle this is
     * @param artifact the produced bundle
     * @return one finding per import with no row, per row this bundle should import and does not,
     *     and per import whose accepted range is not the one the policy chose
     */
    public PolicyReport against(String bundle, BuiltArtifact artifact) {
        final String file = bundle + "/target/" + artifact.archive().getFileName();
        final List<PolicyFinding> findings = new ArrayList<>();
        final SequencedMap<String, String> imported = importedPackages(artifact);
        imported.forEach((name, range) -> {
            if (isRuntimeProvided(name)) {
                return;
            }
            final Optional<PackageRow> row = packages.stream()
                    .filter(candidate -> candidate.name().equals(name)
                            && candidate.bundle().equals(bundle))
                    .findFirst();
            if (row.isEmpty()) {
                findings.add(PolicyFinding.inFile(file, "imported-package",
                        name + " is imported and no row declares it"));
                return;
            }
            if (!row.get().range().equals(range)) {
                findings.add(PolicyFinding.inFile(file, "imported-package-range",
                        name + " accepts " + range + " where the policy chose " + row.get().range()));
            }
        });
        packages.stream()
                .filter(row -> row.bundle().equals(bundle))
                .filter(row -> !imported.containsKey(row.name()))
                .map(row -> PolicyFinding.inFile(POLICY_FILE, "imported-package",
                        row.name() + " has a row and " + bundle + " imports no such package"))
                .forEach(findings::add);
        findings.addAll(embeddingFindings(file, artifact));
        return PolicyReport.of(findings);
    }

    /**
     * Refuses a row no supported deployment universally provides.
     *
     * @param matrix the supported deployment matrix
     * @return one finding per row naming the deployment that does not provide it
     */
    public PolicyReport againstDeployments(DeploymentMatrix matrix) {
        final List<PolicyFinding> findings = new ArrayList<>();
        packages.forEach(row -> matrix.identifiers().stream()
                .filter(deployment -> !row.providedBy().contains(deployment))
                .map(deployment -> PolicyFinding.inFile(POLICY_FILE, "imported-package-provision",
                        row.name() + " is not provided by " + deployment))
                .forEach(findings::add));
        packages.forEach(row -> row.providedBy().stream()
                .filter(deployment -> matrix.row(deployment).isEmpty())
                .map(deployment -> PolicyFinding.inFile(POLICY_FILE, "imported-package-provision",
                        row.name() + " names deployment " + deployment + ", which is not declared"))
                .forEach(findings::add));
        return PolicyReport.of(findings);
    }

    /**
     * Whether a bundle imports anything under a namespace.
     *
     * @param artifact the produced bundle
     * @param namespace the package prefix
     * @return every imported package under that namespace, in the manifest's own order
     */
    public static List<String> importsUnder(BuiltArtifact artifact, String namespace) {
        return importedPackages(artifact).keySet().stream()
                .filter(name -> name.equals(namespace) || name.startsWith(namespace + "."))
                .toList();
    }

    /**
     * Every package a produced bundle imports, with the range each import accepts.
     *
     * @param artifact the produced bundle
     * @return the imports, in the manifest's own order, with an empty range where the clause states
     *     none
     */
    public static SequencedMap<String, String> importedPackages(BuiltArtifact artifact) {
        final SequencedMap<String, String> imports = new LinkedHashMap<>();
        artifact.manifestHeader(IMPORT_HEADER)
                .map(ImportedPackages::clauses)
                .orElseGet(List::of)
                .forEach(clause -> {
                    final List<String> parts = splitOutsideQuotes(clause, ';');
                    imports.put(parts.getFirst().strip(), rangeOf(parts));
                });
        return imports;
    }

    private List<PolicyFinding> embeddingFindings(String file, BuiltArtifact artifact) {
        final List<PolicyFinding> findings = new ArrayList<>();
        artifact.manifestHeader(PRIVATE_HEADER)
                .map(ImportedPackages::clauses)
                .orElseGet(List::of)
                .stream()
                .map(clause -> splitOutsideQuotes(clause, ';').getFirst().strip())
                .filter(name -> !isOwnPackage(name))
                .map(name -> PolicyFinding.inFile(file, "embedded-content",
                        name + " is a private package this repository does not own"))
                .forEach(findings::add);
        List.of(EMBED_HEADER, INCLUDE_RESOURCE_HEADER).forEach(header ->
                artifact.manifestHeader(header)
                        .filter(value -> EMBED_HEADER.equals(header) || value.contains(".jar"))
                        .map(value -> PolicyFinding.inFile(file, "embedded-content",
                                header + " states " + value))
                        .ifPresent(findings::add));
        artifact.manifestHeader(CLASS_PATH_HEADER)
                .filter(value -> !OWN_CLASS_PATH.equals(value.strip()))
                .map(value -> PolicyFinding.inFile(file, "embedded-content",
                        CLASS_PATH_HEADER + " states " + value))
                .ifPresent(findings::add);
        return findings;
    }

    private boolean isRuntimeProvided(String name) {
        return runtimeNamespaces.stream()
                .anyMatch(namespace -> name.startsWith(namespace.namespace()));
    }

    private boolean isOwnPackage(String name) {
        return ownNamespaces.stream()
                .anyMatch(namespace -> name.equals(namespace.namespace())
                        || name.startsWith(namespace.namespace() + "."));
    }

    private static String rangeOf(List<String> clauseParts) {
        return clauseParts.stream()
                .skip(1)
                .map(String::strip)
                .filter(attribute -> attribute.startsWith(VERSION_ATTRIBUTE + "="))
                .map(attribute -> attribute.substring(VERSION_ATTRIBUTE.length() + 1))
                .map(value -> value.replace("\"", ""))
                .findFirst()
                .orElse("");
    }

    private static List<String> clauses(String header) {
        return splitOutsideQuotes(header, ',').stream()
                .map(String::strip)
                .filter(clause -> !clause.isEmpty())
                .toList();
    }

    /**
     * Splits a manifest header on a separator, ignoring separators inside a quoted attribute.
     *
     * <p>A version range is written {@code version="[1.0,2.0)"}, so a header split naively on the
     * comma reports two clauses where there is one.</p>
     */
    private static List<String> splitOutsideQuotes(String text, char separator) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder part = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            }
            if (character == separator && !quoted) {
                parts.add(part.toString());
                part.setLength(0);
                continue;
            }
            part.append(character);
        }
        parts.add(part.toString());
        return List.copyOf(parts);
    }
}
