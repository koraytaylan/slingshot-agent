// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Everything a release consists of, and whether a rebuild can check it.
 *
 * <p>A release is a claim: these bytes, from this source. What makes the first half checkable by
 * somebody who was not there is that the archives are deterministic — so the thing worth asserting
 * at build time is that the source still says so, because the day somebody removes the declared
 * timestamp every rebuild differs and nobody finds out until a release.</p>
 */
final class ReleaseArtifactsTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final long VERIFICATION_TIMEOUT_SECONDS = 10;

    @ParameterizedTest
    @ValueSource(strings = {"same", "changed", "tampered"})
    @DisplayName("the executable verifier compares original recorded bytes despite inventory rewrites")
    void verificationRetainsTheOriginalAuthority(String caseName, @TempDir Path scratch)
            throws IOException, InterruptedException {
        final Path scripts = Files.createDirectories(scratch.resolve("scripts"));
        final Path support = Files.createDirectories(scratch.resolve("support"));
        final Path artifact = scratch.resolve("artifact.txt");
        Files.writeString(artifact, "before\n");
        Files.writeString(support.resolve("release-artifacts.toml"),
                "[[artifact]]\nfile = \"artifact.txt\"\ndigest = \""
                        + ReleaseArtifacts.digestOf(artifact) + "\"\n");
        if ("tampered".equals(caseName)) {
            Files.writeString(artifact, "tampered\n");
        }
        Files.copy(REPOSITORY.resolve("scripts/verify_release_artifacts"),
                scripts.resolve("verify_release_artifacts"));
        final Path builder = scripts.resolve("build_release_artifacts");
        Files.writeString(builder, """
                #!/usr/bin/env bash
                set -eu
                root="$(cd "$(dirname "$0")/.." && pwd)"
                printf '%s\\n' '@OUTPUT@' > "$root/artifact.txt"
                digest="$(sha256sum "$root/artifact.txt" | cut -d' ' -f1)"
                printf '[[artifact]]\\nfile = "artifact.txt"\\ndigest = "sha256:%s"\\n' \\
                    "$digest" > "$root/support/release-artifacts.toml"
                """.replace("@OUTPUT@", "changed".equals(caseName) ? "after" : "before"));
        Files.setPosixFilePermissions(builder, PosixFilePermissions.fromString("rwx------"));
        final Process process = new ProcessBuilder("bash", "scripts/verify_release_artifacts")
                .directory(scratch.toFile())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(VERIFICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "release verification did not finish");
            final String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if ("same".equals(caseName)) {
                assertEquals(0, process.exitValue(), output);
            } else {
                assertTrue(process.exitValue() != 0,
                        "verification accepted " + caseName + " bytes: " + output);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    @DisplayName("the source declares the instant every archive entry carries, not the clock")
    void thearchivesAreDeterministicBecauseTheSourceSaysSo() {
        assertEquals("", ReleaseArtifacts.determinism(REPOSITORY).render());
    }

    @Test
    @DisplayName("a release consists of the container, both bundles, and what the registry requires")
    void areleaseIsMoreThanTheContainer() {
        final List<ReleaseArtifacts.Artifact> artifacts = artifacts().artifacts();
        assertTrue(artifacts.stream().anyMatch(artifact ->
                        "container-package".equals(artifact.kind())),
                "no container package is in the release, and it is the one thing an operator"
                        + " installs");
        assertEquals(2, artifacts.stream().filter(artifact -> "sources".equals(artifact.kind()))
                        .count(),
                "a published module has no sources archive, which the registry requires and"
                        + " somebody debugging inside their own author requires rather more");
        assertEquals(2, artifacts.stream()
                        .filter(artifact -> "documentation".equals(artifact.kind())).count(),
                "a published module has no documentation archive");
        assertTrue(artifacts.stream().anyMatch(artifact -> "components".equals(artifact.kind())),
                "the components list is not in the release, so it would be the one thing nobody"
                        + " verified");
    }

    @Test
    @DisplayName("nothing has been built yet, and the inventory says so rather than claiming bytes")
    void anunbuiltReleaseSaysSo() {
        assertTrue(artifacts().against(REPOSITORY).findings().stream()
                        .allMatch(finding -> ReleaseArtifacts.NOT_BUILT.equals(finding.rule())),
                "the inventory claims a digest for something nobody has built: "
                        + artifacts().against(REPOSITORY).render());
    }

    @Test
    @DisplayName("building and verifying are two commands, because they are two acts")
    void buildingAndVerifyingAreTwoCommands() {
        assertTrue(java.nio.file.Files.isExecutable(
                        REPOSITORY.resolve("scripts/build_release_artifacts")),
                "there is no build command, or it is not runnable");
        assertTrue(java.nio.file.Files.isExecutable(
                        REPOSITORY.resolve("scripts/verify_release_artifacts")),
                "there is no verification command, and a single command that both produced and"
                        + " checked would be one that could only ever agree with itself");
        assertTrue(RepositoryTree.text(REPOSITORY.resolve("scripts/verify_release_artifacts"))
                        .contains("first differing entry"),
                "the verification reports only that archives differ, which sends somebody to look"
                        + " at everything rather than at one thing");
    }

    @Test
    @DisplayName("a digest is the file's own bytes and nothing else")
    void adigestIsTheBytes() {
        final Path aggregator = REPOSITORY.resolve("pom.xml");
        assertEquals(ReleaseArtifacts.digestOf(aggregator), ReleaseArtifacts.digestOf(aggregator),
                "the same file digested to two values, which would make every comparison a coin"
                        + " toss");
        assertTrue(!ReleaseArtifacts.digestOf(aggregator).equals(
                        ReleaseArtifacts.digestOf(REPOSITORY.resolve("CONTRIBUTING.md"))),
                "two different files digested the same");
    }

    private static ReleaseArtifacts artifacts() {
        return assertInstanceOf(ReleaseArtifacts.Loaded.class, ReleaseArtifacts.read(REPOSITORY),
                "the release inventory did not read").artifacts();
    }
}
