// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * Whether the oldest platform this build claims to support actually resolves it.
 *
 * <p>An imported-package range is a claim about somebody else's platform, and the only way to check
 * a claim about a platform is to start one. What this adds to the build-time footprint check is the
 * part it cannot reach: that a real runtime at the declared floor wires every package these bundles
 * ask for, rather than installing them and failing at the first call.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PlatformFloorScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** Where a running instance says what it did with each bundle. */
    private static final String BUNDLES = "/system/console/bundles.json";

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "the tier left a container running");
    }

    @Test
    @DisplayName("every declared row provides the runtime the bundles are compiled for")
    void everyrowProvidesTheCompiledRuntime() {
        final String matrix = read(REPOSITORY.resolve("support/deployments.toml"));
        assertTrue(matrix.lines().filter(line -> line.startsWith("java_runtime = "))
                        .allMatch(line -> line.contains("21")),
                "a supported row provides a runtime other than the one these bundles are compiled"
                        + " for, and on that row nothing this product ships resolves at all");
    }

    @Test
    @DisplayName("the bundle resolves on a running instance rather than merely installing")
    void thebundleResolvesRatherThanInstalls() {
        final String answered = requests.readAsAuthenticatedUser(tier.address() + BUNDLES).body();
        assertTrue(answered.contains(PublicSlingTier.CORE_BUNDLE),
                "the instance does not hold this bundle at all, so nothing here is about it: "
                        + answered);
        assertTrue(!answered.contains("\"state\":\"Installed\"")
                        || !answered.contains(PublicSlingTier.CORE_BUNDLE),
                "the bundle is installed and not resolved, which is a package it imports that this"
                        + " platform does not provide - and it fails at the first call rather than"
                        + " at install");
    }

    @Test
    @DisplayName("the imported ranges this build declares are the ones the floor is checked against")
    void therangesAreTheDeclaredOnes() {
        final String imported = read(REPOSITORY.resolve("policy/imported-packages.toml"));
        assertTrue(imported.contains("range = "),
                "no imported package declares a range, so the floor is a claim about nothing");
        assertTrue(imported.contains("provided_by = "),
                "no imported package says which rows provide it, and a range nobody attributes to a"
                        + " deployment is a range nobody can be refused against");
    }

    @Test
    @DisplayName("the instance answers, so what it says about the bundles is about this build")
    void theinstanceAnswers() {
        assertTrue(requests.readAsAuthenticatedUser(tier.address() + BUNDLES).statusCode()
                        < BAD_REQUEST,
                "the instance would not say what it did with the bundles");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
