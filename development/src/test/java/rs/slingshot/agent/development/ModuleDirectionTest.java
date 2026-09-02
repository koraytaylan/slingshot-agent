// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module diagram, and the six ways an edge can be wrong.
 *
 * <p>The inherited-edge fixture is the one that decides whether the rest mean anything: a module
 * that declares no dependency and is given one by the aggregator has that dependency, and a check
 * reading manifests rather than the resolved model would report nothing at all.</p>
 */
final class ModuleDirectionTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/module-direction");

    @Test
    @DisplayName("this repository's own edges are exactly the ones the policy allows")
    void thisRepositoryHoldsTheDeclaredDirection() {
        final ModuleDirection policy = policyAt(REPOSITORY.resolve("policy/module-direction.toml"));
        assertEquals("", policy.against(ReactorModel.at(REPOSITORY)).render());
    }

    @Test
    @DisplayName("the policy names every module this repository has, and no others")
    void thePolicyNamesEveryModule() {
        final ModuleDirection policy = policyAt(REPOSITORY.resolve("policy/module-direction.toml"));
        assertEquals(ReactorModel.at(REPOSITORY).modules(),
                policy.modules().stream().map(ModuleDirection.ModuleRow::name).toList());
        assertTrue(policy.edges().stream().allMatch(edge -> !edge.reason().isBlank()),
                "an allowed edge records no reason");
    }

    @Test
    @DisplayName("the accepted fixture reactor passes")
    void theAcceptedFixturePasses() {
        assertEquals("", findings("accepted").render());
    }

    @Test
    @DisplayName("the Sling-only bundle depending on the Adobe bundle is refused naming both")
    void coreDependingOnAemIsRefused() {
        assertNames(findings("core-to-aem"), "core depends on aem");
    }

    @Test
    @DisplayName("a product module reaching a tooling module is refused naming both")
    void aProductModuleReachingToolingIsRefused() {
        assertNames(findings("product-to-tooling"), "core depends on development");
    }

    @Test
    @DisplayName("one tooling module reaching the other is refused naming both")
    void oneToolingModuleReachingTheOtherIsRefused() {
        assertNames(findings("tooling-to-tooling"), "development depends on interop");
    }

    @Test
    @DisplayName("a bundle depending on a content package is refused naming both")
    void aBundleDependingOnAContentPackageIsRefused() {
        assertNames(findings("bundle-to-content-package"), "core depends on ui.apps");
    }

    @Test
    @DisplayName("a tooling edge to a product module is allowed at test scope and refused at compile")
    void theToolingEdgeIsPermittedAndItsScopeIsNot() {
        assertEquals("", findings("accepted").render());
        final PolicyReport report = findings("tooling-at-compile-scope");
        assertNames(report, "development depends on core at compile scope");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> "module-direction-scope".equals(finding.rule())),
                "the scope refusal is not reported as its own rule: " + report.render());
    }

    @Test
    @DisplayName("an edge that exists only through the aggregator is caught")
    void anInheritedEdgeIsCaught() {
        final Path fixture = FIXTURES.resolve("inherited-edge");
        assertTrue(!read(readable(fixture.resolve("core/pom.xml"))).contains("<dependencies>"),
                "the fixture module declares a dependency, so the edge is not an inherited one");
        assertNames(findings("inherited-edge"), "core depends on aem");
    }

    @Test
    @DisplayName("a module in no policy row and a policy row naming no module are both refused")
    void membershipIsCheckedInBothDirections() {
        assertNames(findings("module-missing-from-policy"),
                "reporting is in the reactor and in no policy row");
        assertNames(findings("policy-names-absent-module"),
                "interop is in a policy row and in no reactor");
    }

    @Test
    @DisplayName("the Sling-only bundle resolves with no Adobe-namespaced package on its classpath")
    void coreResolvesWithNoAdobePackage() {
        final ModuleDirection policy = policyAt(REPOSITORY.resolve("policy/module-direction.toml"));
        assertEquals("", policy.namespacesOnClasspath(ReactorModel.at(REPOSITORY)).render());
    }

    @Test
    @DisplayName("an artifact carrying an Adobe package on that classpath is refused by name")
    void anAdobeArtifactOnThatClasspathIsRefused(@TempDir Path directory) throws IOException {
        final Path fixture = directory.resolve("adobe-on-the-classpath");
        copyTree(FIXTURES.resolve("accepted"), fixture);
        final Path adobe = directory.resolve("carries-adobe.jar");
        writeArchiveCarrying(adobe, "com/day/cq/wcm/api/Page.class");
        final Path evidence = fixture.resolve("core/target/resolved-compile-classpath.txt");
        Files.createDirectories(parentOf(evidence));
        Files.writeString(evidence, adobe.toString(), StandardCharsets.UTF_8);

        final PolicyReport report = policyAt(fixture.resolve("module-direction.toml"))
                .namespacesOnClasspath(ReactorModel.at(fixture));
        assertNames(report, "carries-adobe.jar carries com.day.cq");
    }

    private static ModuleDirection policyAt(Path document) {
        final ModuleDirection.Outcome outcome = ModuleDirection.readPolicy(document);
        assertTrue(outcome instanceof ModuleDirection.Loaded, "the policy was refused: " + outcome);
        return ((ModuleDirection.Loaded) outcome).policy();
    }

    private static PolicyReport findings(String fixture) {
        final Path root = FIXTURES.resolve(fixture);
        return policyAt(root.resolve("module-direction.toml")).against(ReactorModel.at(root));
    }

    private static void assertNames(PolicyReport report, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream().anyMatch(finding -> finding.symbol().contains(named)),
                "no finding names " + named + ": " + report.render());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (final Path source : walk.toList()) {
                final Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(parentOf(target));
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path parentOf(Path file) {
        final Path parent = file.getParent();
        assertTrue(parent != null, file + " has no directory to create");
        return parent;
    }

    private static void writeArchiveCarrying(Path archive, String entry) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zip.closeEntry();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path readable(Path file) {
        assertTrue(Files.isRegularFile(file), file + " is not there to read");
        return file;
    }
}
