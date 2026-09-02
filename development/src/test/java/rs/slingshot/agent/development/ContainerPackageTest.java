// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the container actually carries, read from the artifact rather than from the configuration.
 *
 * <p>One artifact, whether a customer's own build embeds it or an operator installs it by hand.
 * What it contains, where each part goes, and in what order the parts install is the difference
 * between a package that deploys anywhere and one that works on the machine it was built on — and a
 * Cloud Service deployment embeds it in somebody else's container, where a defect in it surfaces in
 * their pipeline rather than here.</p>
 */
final class ContainerPackageTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** Where inside the container everything installs. */
    private static final String INSTALL_ROOT = "jcr_root/apps/slingshot-agent-packages/application";

    /** The run mode either bundle installs under, and no other. */
    private static final String AUTHOR_ONLY = "install.author";

    /** The root the structure package declares and everything else writes inside. */
    private static final String PRODUCT_ROOT = "/apps/slingshot-agent";

    @Test
    @DisplayName("the container carries exactly the four artifacts the policy declares")
    void theContainerCarriesExactlyWhatIsDeclared() {
        final List<String> carried = container().entryNames().stream()
                .filter(entry -> entry.startsWith(INSTALL_ROOT))
                .filter(entry -> entry.endsWith(".jar") || entry.endsWith(".zip"))
                .map(ContainerPackageTest::artifactName)
                .sorted()
                .toList();
        assertEquals(List.of("slingshot-agent-aem", "slingshot-agent-core", "slingshot-agent-ui.apps",
                        "slingshot-agent-ui.config"),
                carried);
        assertEquals("", analysis().containerContents(ReactorModel.at(REPOSITORY)).render());
    }

    @Test
    @DisplayName("both bundles install under the author run mode and nothing else does")
    void bothBundlesInstallUnderTheAuthorRunModeAlone() {
        final List<String> authorOnly = container().entryNames().stream()
                .filter(entry -> entry.contains(AUTHOR_ONLY))
                .map(ContainerPackageTest::artifactName)
                .sorted()
                .toList();
        assertEquals(List.of("slingshot-agent-aem", "slingshot-agent-core"), authorOnly);
        container().entryNames().stream()
                .filter(entry -> entry.endsWith(".jar"))
                .forEach(entry -> assertTrue(entry.contains(AUTHOR_ONLY),
                        entry + " is a bundle installed on every run mode"));
    }

    @Test
    @DisplayName("the structure package is read and not carried")
    void theStructurePackageIsReadAndNotCarried() {
        assertTrue(container().entryNames().stream()
                        .noneMatch(entry -> entry.contains("ui.apps.structure")),
                "the container carries a package that declares roots and holds nothing");
        assertTrue(ReactorModel.at(REPOSITORY).dependencies("all").stream()
                        .anyMatch(dependency ->
                                "slingshot-agent-ui.apps.structure".equals(dependency.getArtifactId())),
                "the container does not read the structure package at all");
    }

    @Test
    @DisplayName("no package writes outside the roots the structure package declares")
    void nothingWritesOutsideTheDeclaredRoots() {
        final List<String> declared = filterRoots("ui.apps.structure");
        assertEquals(List.of(PRODUCT_ROOT, "/apps/cq/core/content/nav/tools/slingshot-agent"),
                declared);
        List.of("ui.apps", "ui.config").forEach(module -> filterRoots(module)
                .forEach(root -> assertTrue(
                        declared.stream().anyMatch(root::startsWith),
                        module + " writes " + root + ", outside every declared root")));
    }

    @Test
    @DisplayName("the application and the configuration package own subtrees that do not overlap")
    void theTwoPackagesDoNotOverlap() {
        final List<String> application = filterRoots("ui.apps");
        final List<String> configuration = filterRoots("ui.config");
        application.forEach(first -> configuration.forEach(second -> {
            assertFalse(first.startsWith(second + "/") || first.equals(second),
                    first + " and " + second + " overlap, so install order would decide the result");
            assertFalse(second.startsWith(first + "/"),
                    first + " and " + second + " overlap, so install order would decide the result");
        }));
    }

    @Test
    @DisplayName("every content package declares its dependency on the structure package")
    void everyContentPackageDeclaresTheStructureDependency() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        List.of("ui.apps", "ui.config").forEach(module ->
                assertTrue(reactor.dependencies(module).stream()
                                .anyMatch(dependency -> "slingshot-agent-ui.apps.structure"
                                        .equals(dependency.getArtifactId())),
                        module + " writes inside a declared root and does not say so"));
    }

    @Test
    @DisplayName("installing the container twice leaves the same content")
    void installingTwiceLeavesTheSameContent() {
        final List<String> roots = filterRoots("ui.apps");
        final List<String> written = container().entryNames().stream()
                .filter(entry -> entry.startsWith("jcr_root/apps/slingshot-agent/"))
                .toList();
        assertEquals(List.of(), written,
                "the container writes content of its own, which a second install would replace");
        assertTrue(!roots.isEmpty(), "the application package declares no root at all");
    }

    private static String artifactName(String entry) {
        final String file = entry.substring(entry.lastIndexOf('/') + 1);
        return file.substring(0, file.indexOf("-0."));
    }

    private static List<String> filterRoots(String module) {
        final Path filter = REPOSITORY.resolve(module)
                .resolve("src/main/content/META-INF/vault/filter.xml");
        if (!java.nio.file.Files.isRegularFile(filter)) {
            return declaredFilterRoots(module);
        }
        return RepositoryTree.text(filter).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("<filter root="))
                .map(line -> line.split("\"")[1])
                .toList();
    }

    private static List<String> declaredFilterRoots(String module) {
        return ReactorModel.at(REPOSITORY)
                .pluginConfiguration(module, "filevault-package-maven-plugin")
                .map(configuration -> configuration.toString())
                .orElse("")
                .lines()
                .map(String::strip)
                .filter(line -> line.startsWith("<root>"))
                .map(line -> line.replace("<root>", "").replace("</root>", ""))
                .toList();
    }

    private static BuiltArtifact container() {
        final String version = ReactorModel.at(REPOSITORY).aggregator().getVersion();
        return BuiltArtifact.at(REPOSITORY.resolve("all/target/slingshot-agent-all-"
                + version + ".zip"));
    }

    private static PackageAnalysis analysis() {
        return assertInstanceOf(PackageAnalysis.Loaded.class, PackageAnalysis.read(REPOSITORY),
                "the analysis policy was refused").policy();
    }
}
