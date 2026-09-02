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
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The structure this project is allowed to have.
 *
 * <p>Every assertion here reads the effective build model rather than the declared text, so a
 * property inherited from the aggregator is seen exactly as the build sees it. The fixtures beside
 * this test are reactors the rules must refuse; the repository itself is the one they must accept.
 */
final class ProjectScaffoldTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/project-scaffold");

    @Test
    @DisplayName("the repository is exactly the eight declared modules with their declared packaging")
    void repositoryIsTheEightDeclaredModules() {
        assertEquals(List.of(), ProjectScaffold.moduleSetFindings(ReactorModel.at(REPOSITORY)));
    }

    @Test
    @DisplayName("a missing module, an extra module, and a changed packaging type are each refused")
    void moduleSetDeviationsAreRefused() {
        assertRefusedNaming("missing-module", "ui.config", ProjectScaffold::moduleSetFindings);
        assertRefusedNaming("extra-module", "reporting", ProjectScaffold::moduleSetFindings);
        assertRefusedNaming("changed-packaging", "core", ProjectScaffold::moduleSetFindings);
    }

    @Test
    @DisplayName("the four pruned modules appear in no module list, no embed, and no filter")
    void prunedModulesAreAbsentEverywhere() {
        assertEquals(List.of(), ProjectScaffold.prunedModuleFindings(ReactorModel.at(REPOSITORY)));
    }

    @Test
    @DisplayName("a pruned module in the aggregator, in an embed, or in a filter is refused")
    void prunedModuleReappearanceIsRefused() {
        assertRefusedNaming("pruned-module-present", "ui.frontend",
                ProjectScaffold::prunedModuleFindings);
        assertRefusedNaming("embeds-pruned-module", "ui.content",
                ProjectScaffold::prunedModuleFindings);
        assertRefusedNaming("filter-names-pruned-module", "ui.content",
                ProjectScaffold::prunedModuleFindings);
    }

    @Test
    @DisplayName("every module takes its version and group identifier from the aggregator")
    void moduleCoordinatesComeFromTheAggregator() {
        assertEquals(List.of(), ProjectScaffold.coordinateOwnershipFindings(ReactorModel.at(REPOSITORY)));
    }

    @Test
    @DisplayName("a module declaring its own version or group identifier is refused")
    void selfDeclaredCoordinatesAreRefused() {
        assertRefusedNaming("module-declares-version", "core",
                ProjectScaffold::coordinateOwnershipFindings);
        assertRefusedNaming("module-declares-group", "core",
                ProjectScaffold::coordinateOwnershipFindings);
    }

    @Test
    @DisplayName("the group identifier is the package root's own prefix")
    void groupIdentifierIsThePackageRootPrefix() {
        assertEquals(List.of(), ProjectScaffold.namespaceFindings(ReactorModel.at(REPOSITORY)));
        assertRefusedNaming("group-not-package-prefix", "com.example",
                ProjectScaffold::namespaceFindings);
    }

    @Test
    @DisplayName("every Java source sits under the one package root")
    void everyJavaSourceSitsUnderThePackageRoot() {
        assertEquals(List.of(), ProjectScaffold.packageRootFindings(REPOSITORY));
        assertEquals(List.of(),
                ProjectScaffold.packageRootFindings(FIXTURES.resolve("source-inside-root")));
        final List<String> outside =
                ProjectScaffold.packageRootFindings(FIXTURES.resolve("source-outside-root"));
        assertNamesInFindings(outside, "com.example");
    }

    @Test
    @DisplayName("no bundle declares an exported package by instruction rather than by annotation")
    void exportedPackagesAreDeclaredByAnnotationAlone() {
        assertEquals(List.of(), ProjectScaffold.exportInstructionFindings(ReactorModel.at(REPOSITORY)));
        assertRefusedNaming("export-package-instruction", "core",
                ProjectScaffold::exportInstructionFindings);
    }

    @Test
    @DisplayName("the provenance record names exact versions and a reason for every removal")
    void provenanceRecordsExactVersionsAndReasons() {
        assertEquals(List.of(),
                ProjectScaffold.provenanceFindings(REPOSITORY.resolve("support/scaffold-provenance.toml")));
        assertEquals(List.of(),
                ProjectScaffold.provenanceFindings(FIXTURES.resolve("provenance/accepted.toml")));
    }

    @Test
    @DisplayName("a version range, a removal with no reason, and an unknown removal are refused")
    void provenanceDeviationsAreRefused() {
        assertNamesInFindings(ProjectScaffold.provenanceFindings(
                FIXTURES.resolve("provenance/archetype-version-range.toml")), "[57,)");
        assertNamesInFindings(ProjectScaffold.provenanceFindings(
                FIXTURES.resolve("provenance/wrapper-version-range.toml")), "[3.9,4.0)");
        assertNamesInFindings(ProjectScaffold.provenanceFindings(
                FIXTURES.resolve("provenance/removal-without-reason.toml")), "ui.content");
        assertNamesInFindings(ProjectScaffold.provenanceFindings(
                FIXTURES.resolve("provenance/removal-not-a-pruned-module.toml")), "reporting");
    }

    @Test
    @DisplayName("the Maven wrapper is committed and pinned to the recorded distribution")
    void wrapperIsPinnedToTheRecordedVersion() {
        final TomlParseResult provenance = parse(REPOSITORY.resolve("support/scaffold-provenance.toml"));
        final String recorded = provenance.getString("wrapper.maven");
        final Path properties = REPOSITORY.resolve(".mvn/wrapper/maven-wrapper.properties");
        assertTrue(Files.isRegularFile(properties), "the Maven wrapper properties are not committed");
        assertTrue(Files.isExecutable(REPOSITORY.resolve("mvnw")), "mvnw is not committed and executable");
        assertTrue(Files.isRegularFile(REPOSITORY.resolve("mvnw.cmd")), "mvnw.cmd is not committed");
        final String text = read(properties);
        assertTrue(text.contains("apache-maven-" + recorded + "-bin.zip"),
                "the wrapper distribution is not the recorded version " + recorded + ": " + text);
    }

    private static TomlParseResult parse(Path document) {
        try {
            final TomlParseResult result = Toml.parse(document);
            assertTrue(result.errors().isEmpty(), document + " does not parse: " + result.errors());
            return result;
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    // --- assertion helpers -----------------------------------------------------------------

    private static void assertRefusedNaming(String fixture, String named,
                                            Function<ReactorModel, List<String>> rule) {
        assertNamesInFindings(rule.apply(ReactorModel.at(FIXTURES.resolve(fixture))), named);
    }

    private static void assertNamesInFindings(List<String> findings, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream().anyMatch(finding -> finding.contains(named)),
                "no finding names " + named + ": " + findings);
    }
}
