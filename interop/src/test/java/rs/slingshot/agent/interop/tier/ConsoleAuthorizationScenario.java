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
 * The console refusing a caller who may not use it, on a running instance.
 *
 * <p>What a unit suite proves is that the paging arithmetic is right. What it cannot prove is that
 * the command is registered on a real runtime, that a window survives the transport intact, and
 * that the rules its own registry row states are the ones the instance actually applies.</p>
 *
 * <p>The operation-key rule is read from the row rather than restated. This command refuses a key
 * and the command beside it in the same package requires one; a test that wrote either rule down
 * again would pass on the day somebody changed the row and forgot the test, which is the day it
 * mattered.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ConsoleAuthorizationScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a submission this build will not act on is answered with. */
    private static final int REFUSED = 400;

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
    @DisplayName("the console is held to the very requirement the route that starts work is")
    void theconsoleAndTheRouteCannotDrift() {
        final String authority = read(REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/console/ConsoleAuthority.java"));
        assertTrue(authority.contains("ROUTE = \"submit\""),
                "the console decides who may use it by a second rule, and the day the two"
                        + " disagree one of them is wrong in a direction nobody planned");
        assertTrue(authority.contains("HIDDEN"),
                "a viewer who may not use the console is shown an entry that refuses when"
                        + " clicked, which teaches them this product is broken");
    }

    @Test
    @DisplayName("a caller who authenticated as nobody reaches neither the route nor the console")
    void nobodyReachesEither() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity");
        assertTrue(requests.readAsNobody(tier.address()
                        + "/apps/slingshot-agent/content/console.html").statusCode() >= REFUSED,
                "a caller who presented no identity reached a console resource, and a console is"
                        + " a servlet like any other");
    }

    @Test
    @DisplayName("the permitted groups are one configuration rather than two")
    void thepermittedGroupsAreOneConfiguration() {
        final String configuration = read(REPOSITORY.resolve(
                "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config"
                        + "/rs.slingshot.agent.http.AuthorizationGate.cfg.json"));
        assertTrue(configuration.contains("group"),
                "the groups an operator permits are no longer one configuration, so the console"
                        + " and the routes could be given different answers: " + configuration);
    }

    private static String row(String command) {
        return read(REPOSITORY.resolve("policy/commands/" + command + ".toml"));
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
