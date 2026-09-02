// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bytecode contract, checked against the bytes every module actually produced.
 *
 * <p>The last assertion here is the one that decides whether the rest are worth anything: a fixture
 * whose build model says twenty-one and whose class file says twenty is refused, which is only
 * possible because the check reads the archive rather than the property.</p>
 */
final class BytecodeContractTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/bytecode-contract");

    /** The modules that produce a jar full of this repository's own classes. */
    private static final List<String> JAR_MODULES = List.of("core", "aem", "development", "interop");

    @Test
    @DisplayName("the declared release is twenty-one and its class-file version is sixty-five")
    void theDeclaredReleaseIsTwentyOne() {
        assertEquals(21, BytecodeContract.DECLARED_RELEASE);
        assertEquals(65, BytecodeContract.declaredClassFileMajorVersion());
    }

    @Test
    @DisplayName("every class in every built artifact carries exactly the declared version")
    void everyBuiltClassCarriesTheDeclaredVersion() {
        final PolicyReport report = JAR_MODULES.stream()
                .map(BytecodeContractTest::builtArtifactOf)
                .map(artifact -> BytecodeContract.inArtifact(
                        REPOSITORY.relativize(artifact.archive()).toString(), artifact))
                .reduce(PolicyReport.empty(), PolicyReport::and);
        assertEquals("", report.render());
    }

    @Test
    @DisplayName("a class file one version above the contract and one below are both refused")
    void aClassFileEitherSideOfTheContractIsRefused() {
        final PolicyReport above = BytecodeContract.inArtifact("one-above.jar",
                BuiltArtifact.at(FIXTURES.resolve("one-above.jar")));
        assertNames(above, "66");
        final PolicyReport below = BytecodeContract.inArtifact("one-below.jar",
                BuiltArtifact.at(FIXTURES.resolve(
                        "model-disagrees-with-bytes/module/built/bytecode-module-1.0.0.jar")));
        assertNames(below, "64");
    }

    @Test
    @DisplayName("the check reads the bytes, so a model that says twenty-one cannot rescue them")
    void theModelCannotRescueTheBytes() {
        final Path fixture = FIXTURES.resolve("model-disagrees-with-bytes");
        final ReactorModel reactor = ReactorModel.at(fixture);
        assertEquals("", BytecodeContract.releaseDeclarations(reactor).render(),
                "the fixture's model is the accepted one");
        final BuiltArtifact produced =
                BuiltArtifact.at(fixture.resolve("module/built/bytecode-module-1.0.0.jar"));
        assertNames(BytecodeContract.inArtifact("module/built/bytecode-module-1.0.0.jar", produced),
                "Target.class");
    }

    @Test
    @DisplayName("the accepted fixture reactor passes every model-level rule")
    void theAcceptedFixturePasses() {
        final ReactorModel accepted = ReactorModel.at(FIXTURES.resolve("accepted"));
        assertEquals("", BytecodeContract.releaseDeclarations(accepted).render());
        assertEquals("", BytecodeContract.warningsAsErrors(accepted).render());
        assertEquals("", BytecodeContract.inArtifact("accepted",
                BuiltArtifact.at(FIXTURES.resolve("accepted/module/built/bytecode-module-1.0.0.jar")))
                .render());
    }

    @Test
    @DisplayName("a module that declares its own release level is refused by name")
    void aModuleDeclaringItsOwnReleaseIsRefused() {
        assertNames(BytecodeContract.releaseDeclarations(
                ReactorModel.at(FIXTURES.resolve("module-declares-release"))), "maven.compiler.release");
    }

    @Test
    @DisplayName("a source or target level set anywhere is refused")
    void aSourceOrTargetLevelIsRefused() {
        final PolicyReport report = BytecodeContract.releaseDeclarations(
                ReactorModel.at(FIXTURES.resolve("source-and-target-set")));
        assertNames(report, "maven.compiler.source");
        assertNames(report, "maven.compiler.target");
    }

    @Test
    @DisplayName("a module that turns warnings-as-errors off or turns linting down is refused")
    void weakenedCompilationIsRefused() {
        assertNames(BytecodeContract.warningsAsErrors(
                ReactorModel.at(FIXTURES.resolve("warnings-not-errors"))), "does not fail on a warning");
        assertNames(BytecodeContract.warningsAsErrors(
                ReactorModel.at(FIXTURES.resolve("linting-turned-down"))), "-Xlint:all");
        assertNames(BytecodeContract.releaseDeclarations(
                ReactorModel.at(FIXTURES.resolve("linting-turned-down"))), "-Xlint:none");
    }

    @Test
    @DisplayName("this repository declares the release once and fails on every warning everywhere")
    void thisRepositoryHoldsTheContract() {
        final ReactorModel reactor = ReactorModel.at(REPOSITORY);
        assertEquals("", BytecodeContract.releaseDeclarations(reactor).render());
        assertEquals("", BytecodeContract.warningsAsErrors(reactor).render());
    }

    private static BuiltArtifact builtArtifactOf(String module) {
        final Path target = REPOSITORY.resolve(module).resolve("target");
        final Path archive = target.resolve("slingshot-agent-" + module + "-"
                + ReactorModel.at(REPOSITORY).aggregator().getVersion() + ".jar");
        return BuiltArtifact.at(archive);
    }

    private static void assertNames(PolicyReport report, String named) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(report.findings().stream().anyMatch(finding -> finding.symbol().contains(named)),
                "no finding names " + named + ": " + report.render());
    }
}
