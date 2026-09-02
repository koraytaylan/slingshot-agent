// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every artifact this build resolves, and the four ways a version can stop being one version.
 *
 * <p>The rule that matters most is the scope one. Nothing a product module resolves may be at
 * compile or runtime scope, because everything a bundle needs at runtime is provided by the
 * deployment — and that is what makes the imported-package footprint the whole compatibility
 * statement rather than half of one.</p>
 */
final class DependencyPolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/dependency-policy");

    /** The modules whose resolved sets the policy covers. */
    private static final List<String> RESOLVING_MODULES =
            List.of("core", "aem", "development", "interop");

    @Test
    @DisplayName("every artifact this build resolves has a row, and every row is resolved")
    void theDeclaredSetIsExactlyTheResolvedOne() {
        assertEquals("", policy().against(ReactorModel.at(REPOSITORY), RESOLVING_MODULES).render());
    }

    @Test
    @DisplayName("every row names an exact version and records why the build has it")
    void everyRowIsExactAndReasoned() {
        assertEquals(List.of(), policy().versionShapeFindings());
        policy().artifacts().forEach(row -> {
            assertTrue(!row.reason().isBlank(), row.coordinate() + " records no reason");
            assertTrue(!row.modules().isEmpty(), row.coordinate() + " names no module");
        });
    }

    @Test
    @DisplayName("a range and a snapshot are each refused")
    void aRangeAndASnapshotAreRefused() {
        assertRule(PolicyReport.of(policyAt("version-range.toml").versionShapeFindings()),
                "dependency-version", "org.example:declared is pinned to the range [1.0,2.0)");
        assertRule(PolicyReport.of(policyAt("snapshot-version.toml").versionShapeFindings()),
                "dependency-version", "1.0.0-SNAPSHOT, which is a name rather than a version");
    }

    @Test
    @DisplayName("a row with no reason refuses the whole policy")
    void aRowWithNoReasonRefusesThePolicy() {
        assertInstanceOf(DependencyPolicy.Refused.class,
                DependencyPolicy.readPolicy(FIXTURES.resolve("row-with-no-reason.toml")),
                "an artifact resolved with no reason recorded was accepted");
    }

    @Test
    @DisplayName("a product module resolving anything at compile scope is refused")
    void compileScopeInAProductModuleIsRefused() {
        assertRule(policy().againstResolved(resolved("core", new ReactorModel.ResolvedArtifact(
                        "org.example", "embedded", "jar", "1.0.0", "compile"))),
                "dependency-scope", "org.example:embedded is resolved by core at compile scope");
        assertRule(policy().againstResolved(resolved("core", new ReactorModel.ResolvedArtifact(
                        "org.example", "embedded", "jar", "1.0.0", "runtime"))),
                "dependency-scope", "at runtime scope");
    }

    @Test
    @DisplayName("an artifact with no row and a row nothing resolves are refused distinctly")
    void bothDirectionsAreChecked() {
        assertRule(policy().againstResolved(resolved("core", new ReactorModel.ResolvedArtifact(
                        "org.example", "unlisted", "jar", "1.0.0", "provided"))),
                "dependency-policy", "org.example:unlisted is resolved by core and has no row");
        assertRule(policyAt("accepted.toml").againstResolved(resolved("core")),
                "dependency-policy", "org.example:declared has a row and nothing resolves it");
    }

    @Test
    @DisplayName("only the preparation commands say they reach the network, and each is a preparation")
    void onlyPreparationCommandsReachTheNetwork() {
        assertEquals(List.of("prepare_interop_images", "prepare_locked_dependency_cache"),
                DependencyPolicy.networkReachingScripts(REPOSITORY));
        DependencyPolicy.networkReachingScripts(REPOSITORY).forEach(command ->
                assertTrue(command.startsWith("prepare_"),
                        command + " reaches the network and is not a preparation command"));
    }

    @Test
    @DisplayName("exactly one of them resolves a dependency from a remote repository")
    void oneCommandResolvesADependency() {
        final List<String> resolving = DependencyPolicy.networkReachingScripts(REPOSITORY).stream()
                .filter(command -> RepositoryTree
                        .text(REPOSITORY.resolve("scripts").resolve(command))
                        .contains("dependency:go-offline"))
                .toList();
        assertEquals(List.of("prepare_locked_dependency_cache"), resolving);
    }

    @Test
    @DisplayName("the verification command says what it establishes and what it does not")
    void verificationStatesWhatItEstablishes() {
        final String verification =
                RepositoryTree.text(REPOSITORY.resolve("scripts/verify_locked_dependency_cache"));
        assertTrue(verification.contains("unchanged since"),
                "the verification command does not say what it establishes");
        assertTrue(verification.contains("trustworthy when they were fetched"),
                "the verification command does not say what it does not establish");
        assertTrue(!verification.contains(DependencyPolicy.NETWORK_DECLARATION),
                "the verification command declares that it reaches the network");
    }

    private static SequencedMap<String, List<ReactorModel.ResolvedArtifact>> resolved(
            String module, ReactorModel.ResolvedArtifact... artifacts) {
        final SequencedMap<String, List<ReactorModel.ResolvedArtifact>> byModule =
                new LinkedHashMap<>();
        byModule.put(module, List.of(artifacts));
        return byModule;
    }

    private static DependencyPolicy policy() {
        return assertInstanceOf(DependencyPolicy.Loaded.class, DependencyPolicy.read(REPOSITORY),
                "the dependency policy was refused").policy();
    }

    private static DependencyPolicy policyAt(String fixture) {
        return assertInstanceOf(DependencyPolicy.Loaded.class,
                DependencyPolicy.readPolicy(FIXTURES.resolve(fixture)),
                fixture + " was refused").policy();
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + report.render());
    }
}
