// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The footprint both bundles are allowed to have, read from the manifests the build produced.
 *
 * <p>The two-way comparison is the point. An import with no row is a footprint that grew without
 * anybody choosing it; a row nothing imports is a claim about a dependency that has gone. Either
 * one on its own would let the file drift away from the artifact it describes.</p>
 */
final class ImportedPackagesTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/imported-packages");

    /** The namespaces the Sling-only bundle must never import, whatever else changes. */
    private static final List<String> ADOBE_NAMESPACES = List.of("com.day.cq", "com.adobe");

    @Test
    @DisplayName("both built bundles hold the footprint this repository declares")
    void bothBuiltBundlesHoldTheirFootprint() {
        final ImportedPackages policy = committedPolicy();
        assertEquals("", policy.against("core", builtBundle("core")).render());
        assertEquals("", policy.against("aem", builtBundle("aem")).render());
    }

    @Test
    @DisplayName("every declared row is provided by every supported deployment")
    void everyRowIsUniversallyProvided() {
        assertEquals("", committedPolicy().againstDeployments(deployments()).render());
    }

    @Test
    @DisplayName("the Sling-only bundle imports nothing Adobe-namespaced, read from its manifest")
    void theSlingOnlyBundleImportsNothingAdobeNamespaced() {
        ADOBE_NAMESPACES.forEach(namespace ->
                assertEquals(List.of(), ImportedPackages.importsUnder(builtBundle("core"), namespace)));
    }

    @Test
    @DisplayName("the check reports the exact import set it read from the manifest")
    void theCheckReportsTheImportSetItRead() {
        assertEquals(List.of("java.util", "java.io", "org.apache.sling.api", "javax.jcr"),
                List.copyOf(ImportedPackages.importedPackages(fixtureBundle("accepted.jar")).keySet()));
        assertEquals("[2.27,3)",
                ImportedPackages.importedPackages(fixtureBundle("accepted.jar")).get("org.apache.sling.api"));
        assertEquals("", acceptedPolicy().against("core", fixtureBundle("accepted.jar")).render());
    }

    @Test
    @DisplayName("an unlisted import, an unused row, and a widened range are refused distinctly")
    void theThreeFootprintDeviationsAreRefusedDistinctly() {
        assertRule(acceptedPolicy().against("core", fixtureBundle("unlisted-import.jar")),
                "imported-package", "org.nobody.declared");
        assertRule(acceptedPolicy().against("core", fixtureBundle("unused-row.jar")),
                "imported-package", "javax.jcr");
        assertRule(acceptedPolicy().against("core", fixtureBundle("widened-range.jar")),
                "imported-package-range", "[2.0,4)");
    }

    @Test
    @DisplayName("a private package this repository does not own is refused")
    void aForeignPrivatePackageIsRefused() {
        assertRule(acceptedPolicy().against("core", fixtureBundle("foreign-private-package.jar")),
                "embedded-content", "org.apache.commons.lang3");
    }

    @Test
    @DisplayName("an embedding instruction, an included jar, and a second class-path entry are refused")
    void everyWayOfArrivingInsideTheArtifactIsRefused() {
        assertRule(acceptedPolicy().against("core", fixtureBundle("embeds-a-dependency.jar")),
                "embedded-content", "Embed-Dependency");
        assertRule(acceptedPolicy().against("core", fixtureBundle("includes-a-jar.jar")),
                "embedded-content", "Include-Resource");
        assertRule(acceptedPolicy().against("core", fixtureBundle("second-class-path-entry.jar")),
                "embedded-content", "Bundle-ClassPath");
    }

    @Test
    @DisplayName("an Adobe import in the Sling-only bundle is seen from the manifest")
    void anAdobeImportIsSeenFromTheManifest() {
        assertEquals(List.of("com.day.cq.wcm.api"),
                ImportedPackages.importsUnder(fixtureBundle("imports-adobe.jar"), "com.day.cq"));
    }

    @Test
    @DisplayName("a row no supported deployment universally provides is refused naming the deployment")
    void aPartiallyProvidedRowIsRefused() {
        assertRule(policyAt("partially-provided-policy.toml").againstDeployments(deployments()),
                "imported-package-provision", "aem-6-5-lts");
    }

    @Test
    @DisplayName("a row naming a deployment that is not declared is refused naming it")
    void aRowNamingAnUndeclaredDeploymentIsRefused() {
        assertRule(policyAt("unknown-deployment-policy.toml").againstDeployments(deployments()),
                "imported-package-provision", "aem-4-2");
    }

    private static ImportedPackages committedPolicy() {
        return loaded(ImportedPackages.read(REPOSITORY));
    }

    private static ImportedPackages acceptedPolicy() {
        return policyAt("accepted-policy.toml");
    }

    private static ImportedPackages policyAt(String fixture) {
        return loaded(ImportedPackages.readPolicy(FIXTURES.resolve(fixture)));
    }

    private static ImportedPackages loaded(ImportedPackages.Outcome outcome) {
        return assertInstanceOf(ImportedPackages.Loaded.class, outcome,
                "the policy was refused: " + outcome).policy();
    }

    private static DeploymentMatrix deployments() {
        final DeploymentMatrix.Outcome outcome =
                DeploymentMatrix.load(REPOSITORY.resolve("support/deployments.toml"));
        return assertInstanceOf(DeploymentMatrix.Loaded.class, outcome,
                "the deployment matrix was refused: " + outcome).matrix();
    }

    private static BuiltArtifact builtBundle(String module) {
        final String version = ReactorModel.at(REPOSITORY).aggregator().getVersion();
        return BuiltArtifact.at(REPOSITORY.resolve(module).resolve("target")
                .resolve("slingshot-agent-" + module + "-" + version + ".jar"));
    }

    private static BuiltArtifact fixtureBundle(String fixture) {
        return BuiltArtifact.at(FIXTURES.resolve(fixture));
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule()) && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + report.render());
    }
}
