// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.maven.model.Dependency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The four things every repository-policy check reads, held to their own contract.
 *
 * <p>The toolkit is checked here rather than incidentally by the checks built on it, because a
 * defect in a shared reader is a defect in every rule at once, and a rule that reported a finding
 * against a partly-populated document would be reporting on a policy nobody wrote.</p>
 */
final class PolicyToolkitTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/policy-toolkit");

    /** The scopes a tooling module's dependency may be resolved at, and no other. */
    private static final List<String> TOOLING_SCOPES = List.of("test", "provided");

    /** The modules this product ships, none of which may reach a tooling module. */
    private static final List<String> PRODUCT_MODULES =
            List.of("core", "aem", "ui.apps.structure", "ui.apps", "ui.config", "all");

    /** The modules that hold the checks, which the product modules never reach. */
    private static final List<String> TOOLING_MODULES = List.of("development", "interop");

    private static PolicyDocument.Shape fixtureShape() {
        return PolicyDocument.Shape.named("toolkit-fixture")
                .text("title")
                .number("count")
                .answer("strict")
                .text("origin.name")
                .freeTable("free")
                .rows("row", row -> row.text("name").text("reason"))
                .build();
    }

    // --- the document reader ---------------------------------------------------------------

    @Test
    @DisplayName("a well-formed document parses to exactly the keys its shape declares")
    void wellFormedDocumentParsesToItsDeclaredKeys() {
        final PolicyDocument document = loaded("well-formed.toml");
        assertEquals(List.of("title", "count", "strict", "origin.name", "free", "row"),
                List.copyOf(document.keys()));
        assertEquals("accepted", document.text("title"));
        assertEquals(3L, document.number("count"));
        assertTrue(document.answer("strict"));
        assertEquals("slingshot", document.text("origin.name"));
        assertEquals(List.of("anything", "so"), List.copyOf(document.freeTable("free").keySet()));
        assertEquals(List.of("first", "second"),
                document.rows("row").stream().map(row -> row.text("name")).toList());
        assertEquals("also because", document.rows("row").get(1).text("reason"));
    }

    @Test
    @DisplayName("a duplicate key, an unknown key, and an out-of-type value are refused distinctly")
    void theThreeDocumentFailuresAreDistinct() {
        assertEquals(PolicyDocument.Failure.DUPLICATE_KEY, refusal("duplicate-key.toml").failure());
        assertEquals(PolicyDocument.Failure.UNKNOWN_KEY, refusal("unknown-key.toml").failure());
        assertEquals(PolicyDocument.Failure.WRONG_TYPE, refusal("out-of-type.toml").failure());
    }

    @Test
    @DisplayName("a missing key and bytes that are not a document at all are refused distinctly too")
    void absenceAndMalformedBytesAreTheirOwnFailures() {
        assertEquals(PolicyDocument.Failure.MISSING_KEY, refusal("missing-key.toml").failure());
        assertEquals(PolicyDocument.Failure.UNPARSABLE, refusal("unparsable.toml").failure());
        assertEquals(PolicyDocument.Failure.UNREADABLE,
                refusal(PolicyDocument.load(FIXTURES.resolve("no-such-document.toml"), fixtureShape()))
                        .failure());
    }

    @Test
    @DisplayName("a repeated table's rows are held to the row shape in both directions")
    void repeatedTableRowsAreHeldToTheirOwnShape() {
        assertEquals(PolicyDocument.Failure.UNKNOWN_KEY, refusal("row-unknown-key.toml").failure());
        assertEquals(PolicyDocument.Failure.MISSING_KEY, refusal("row-missing-key.toml").failure());
    }

    @Test
    @DisplayName("no refusal leaves a partly-populated document reachable")
    void aRefusalYieldsNoDocument() {
        List.of("duplicate-key.toml", "unknown-key.toml", "out-of-type.toml", "missing-key.toml",
                        "unparsable.toml", "row-unknown-key.toml", "row-missing-key.toml")
                .forEach(fixture -> assertInstanceOf(PolicyDocument.Refused.class,
                        PolicyDocument.load(FIXTURES.resolve(fixture), fixtureShape()),
                        fixture + " produced something other than a refusal"));
    }

    @Test
    @DisplayName("every refusal names the document and the key it refused")
    void everyRefusalNamesWhatItRefused() {
        assertTrue(refusal("unknown-key.toml").detail().contains("extra"));
        assertTrue(refusal("out-of-type.toml").detail().contains("count"));
        assertTrue(refusal("missing-key.toml").detail().contains("strict"));
        assertTrue(refusal("row-unknown-key.toml").detail().contains("colour"));
        assertTrue(refusal("row-missing-key.toml").detail().contains("reason"));
    }

    // --- the report ------------------------------------------------------------------------

    @Test
    @DisplayName("a report over several files renders byte-identically across two runs")
    void aReportRendersByteIdenticallyTwice() {
        final PolicyReport first = PolicyReport.of(scrambledFindings());
        final PolicyReport second = PolicyReport.of(reversed(scrambledFindings()));
        assertArrayEquals(first.render().getBytes(StandardCharsets.UTF_8),
                second.render().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a report is ordered by file, then line, then rule, then symbol")
    void aReportIsOrderedByFileThenLineThenRuleThenSymbol() {
        final PolicyReport report = PolicyReport.of(scrambledFindings());
        assertEquals(List.of(
                        "all/pom.xml\tmodule-direction\tslingshot-agent-core",
                        "core/src/main/java/rs/slingshot/agent/Alpha.java:4\tnullability\treturn",
                        "core/src/main/java/rs/slingshot/agent/Alpha.java:12\tapi-shape\tAlphaImpl",
                        "core/src/main/java/rs/slingshot/agent/Alpha.java:12\tmethod-shape\tnesting",
                        "core/src/main/java/rs/slingshot/agent/Beta.java:1\tdocumentation\tpackage"),
                report.findings().stream().map(PolicyFinding::render).toList());
    }

    @Test
    @DisplayName("a report holds a repeated finding once and an empty report renders nothing")
    void repeatedFindingsAreHeldOnce() {
        final PolicyFinding finding = PolicyFinding.inFile("all/pom.xml", "module-direction", "x");
        assertEquals(1, PolicyReport.of(List.of(finding, finding)).findings().size());
        assertTrue(PolicyReport.empty().isEmpty());
        assertEquals("", PolicyReport.empty().render());
        assertFalse(PolicyReport.of(List.of(finding)).isEmpty());
    }

    // --- the build model -------------------------------------------------------------------

    @Test
    @DisplayName("the model sees a dependency a module has only through the aggregator")
    void theModelSeesInheritedAndManagedDependencies() {
        final ReactorModel fixture = ReactorModel.at(FIXTURES.resolve("managed-reactor"));
        final List<Dependency> effective = fixture.dependencies("module");
        assertEquals(List.of("org.example:managed:2.0.0:test", "org.example:inherited:1.0.0:test"),
                effective.stream().map(PolicyToolkitTest::render).toList());
        assertEquals(List.of("org.example:managed:none:none"),
                fixture.raw("module").getDependencies().stream()
                        .map(PolicyToolkitTest::render).toList());
    }

    @Test
    @DisplayName("the model reads this repository's own eight modules")
    void theModelReadsThisRepository() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        assertEquals(List.of("core", "aem", "ui.apps.structure", "ui.apps", "ui.config", "all",
                "development", "interop"), reactor.modules());
        assertEquals("rs.slingshot", reactor.aggregator().getGroupId());
    }

    // --- the produced artifact -------------------------------------------------------------

    @Test
    @DisplayName("the artifact reader reads entries that exist only in the produced archive")
    void theArtifactReaderReadsProducedBytes(@TempDir Path directory) {
        final Path archive = directory.resolve("produced.jar");
        final byte[] classFile = producedClassFile();
        writeArchive(archive, classFile);

        final BuiltArtifact artifact = BuiltArtifact.at(archive);
        assertEquals(List.of("META-INF/MANIFEST.MF", "rs/slingshot/agent/development/PolicyFinding.class"),
                artifact.entryNames());
        assertEquals("slingshot-agent-toolkit-fixture",
                artifact.manifestHeader("Bundle-SymbolicName").orElseThrow());
        assertTrue(artifact.manifestHeader("Export-Package").isEmpty());
        assertTrue(artifact.holds("META-INF/MANIFEST.MF"));
        assertFalse(artifact.holds("META-INF/nothing"));
        assertEquals(List.of(65),
                List.copyOf(artifact.classFileMajorVersions().sequencedValues()));
    }

    // --- the tooling dependency set ---------------------------------------------------------

    @Test
    @DisplayName("every tooling dependency is managed by the aggregator and never at compile scope")
    void theToolingDependencySetIsExactlyTheDeclaredOne() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        final List<PolicyFinding> findings = new ArrayList<>();
        TOOLING_MODULES.forEach(module -> {
            reactor.raw(module).getDependencies().stream()
                    .filter(declared -> declared.getVersion() != null)
                    .forEach(declared -> findings.add(PolicyFinding.inFile(module + "/pom.xml",
                            "tooling-dependency-version", coordinate(declared))));
            reactor.dependencies(module).stream()
                    .filter(resolved -> !TOOLING_SCOPES.contains(resolved.getScope()))
                    .forEach(resolved -> findings.add(PolicyFinding.inFile(module + "/pom.xml",
                            "tooling-dependency-scope", coordinate(resolved) + ":" + resolved.getScope())));
        });
        assertEquals(PolicyReport.empty().render(), PolicyReport.of(findings).render());
    }

    @Test
    @DisplayName("nothing this product ships reaches a module that holds the checks")
    void noProductModuleReachesAToolingModule() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        final List<String> toolingArtifacts =
                TOOLING_MODULES.stream().map(module -> reactor.effective(module).getArtifactId()).toList();
        final List<PolicyFinding> findings = PRODUCT_MODULES.stream()
                .flatMap(module -> reactor.dependencies(module).stream()
                        .filter(dependency -> toolingArtifacts.contains(dependency.getArtifactId()))
                        .map(dependency -> PolicyFinding.inFile(module + "/pom.xml",
                                "module-direction", coordinate(dependency))))
                .toList();
        assertEquals(PolicyReport.empty().render(), PolicyReport.of(findings).render());
    }

    @Test
    @DisplayName("the two tooling modules do not reach one another")
    void theToolingModulesDoNotReachOneAnother() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        assertTrue(reactor.dependencies("development").stream()
                .noneMatch(dependency -> "slingshot-agent-interop".equals(dependency.getArtifactId())));
        assertTrue(reactor.dependencies("interop").stream()
                .noneMatch(dependency -> "slingshot-agent-development".equals(dependency.getArtifactId())));
    }

    @Test
    @DisplayName("the build writes down what it resolved, and the model reads it back")
    void theBuildRecordsWhatItResolved() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        final List<ReactorModel.ResolvedArtifact> resolved = reactor.resolvedDependencies("development");
        assertFalse(resolved.isEmpty(), "the build recorded no resolved dependency set");
        assertTrue(resolved.stream().anyMatch(artifact -> "org.tomlj:tomlj".equals(artifact.coordinate())),
                "the resolved set does not hold the declarative-document reader: " + resolved);
        assertTrue(resolved.stream().noneMatch(artifact -> "compile".equals(artifact.scope())),
                "a tooling artifact resolved at compile scope: " + resolved);
    }

    // --- helpers ----------------------------------------------------------------------------

    private static List<PolicyFinding> scrambledFindings() {
        final String alpha = "core/src/main/java/rs/slingshot/agent/Alpha.java";
        final String beta = "core/src/main/java/rs/slingshot/agent/Beta.java";
        return List.of(
                new PolicyFinding(alpha, 12, "method-shape", "nesting"),
                new PolicyFinding(beta, 1, "documentation", "package"),
                new PolicyFinding(alpha, 4, "nullability", "return"),
                PolicyFinding.inFile("all/pom.xml", "module-direction", "slingshot-agent-core"),
                new PolicyFinding(alpha, 12, "api-shape", "AlphaImpl"));
    }

    private static List<PolicyFinding> reversed(List<PolicyFinding> findings) {
        final List<PolicyFinding> other = new ArrayList<>(findings);
        Collections.reverse(other);
        return other;
    }

    private static String render(Dependency dependency) {
        final String version = dependency.getVersion() == null ? "none" : dependency.getVersion();
        final String scope = dependency.getScope() == null ? "none" : dependency.getScope();
        return coordinate(dependency) + ":" + version + ":" + scope;
    }

    private static String coordinate(Dependency dependency) {
        return dependency.getGroupId() + ":" + dependency.getArtifactId();
    }

    private static PolicyDocument loaded(String fixture) {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(FIXTURES.resolve(fixture), fixtureShape());
        return assertInstanceOf(PolicyDocument.Loaded.class, outcome,
                fixture + " was refused: " + outcome).document();
    }

    private static PolicyDocument.Refused refusal(String fixture) {
        return refusal(PolicyDocument.load(FIXTURES.resolve(fixture), fixtureShape()));
    }

    private static PolicyDocument.Refused refusal(PolicyDocument.Outcome outcome) {
        return assertInstanceOf(PolicyDocument.Refused.class, outcome,
                "the reader accepted what it must refuse");
    }

    private static byte[] producedClassFile() {
        final Path produced = REPOSITORY.resolve(
                "development/target/classes/rs/slingshot/agent/development/PolicyFinding.class");
        assertTrue(Files.isRegularFile(produced), "the build produced no class file at " + produced);
        try {
            return Files.readAllBytes(produced);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeArchive(Path archive, byte[] classFile) {
        final String manifest = "Manifest-Version: 1.0\r\n"
                + "Bundle-SymbolicName: slingshot-agent-toolkit-fixture\r\n\r\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("rs/slingshot/agent/development/PolicyFinding.class"));
            zip.write(classFile);
            zip.closeEntry();
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
