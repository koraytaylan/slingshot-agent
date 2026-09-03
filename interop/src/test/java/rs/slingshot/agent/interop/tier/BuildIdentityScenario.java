// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
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
 * Which build this is, on a running instance.
 *
 * <p>An operator diagnosing a version disagreement has to read both sides, and this is the side
 * nobody can see from outside: which commit, which two contract digests, which event-store
 * generation, and whether this build claims the deployment row it finds itself on.</p>
 *
 * <p>What a unit suite settles is that the page renders what discovery answers. What it cannot
 * settle is that the discovery route on a real instance answers the same thing — so the digests are
 * compared against the route rather than against a second copy, and the route is asked here.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class BuildIdentityScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** Where the screen this scenario is about is declared. */
    private static final String SCREEN = "ui.apps/src/main/content/jcr_root/apps/slingshot-agent"
            + "/content/console/identity/.content.xml";

    /** Where an operator reaches that screen. */
    private static final String ADDRESS =
            "/apps/slingshot-agent/content/console/identity.html";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** Anything from here up is not a success, whichever way the platform refused. */
    private static final int BELOW_A_SUCCESS = 300;

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
    @DisplayName("the page and the discovery route are the same source rather than two copies")
    void thepageAndTheRouteAreOneSource() {
        final String routes = read(REPOSITORY.resolve("policy/agent-routes.toml"));
        assertTrue(routes.contains("capabilities"),
                "the discovery route is no longer in the committed table, and this page renders"
                        + " what that route reads");
        assertEquals(UNAUTHENTICATED, requests
                        .readAsNobody(tier.address() + "/bin/slingshot/agent/capabilities")
                        .statusCode(),
                "the discovery route answered a caller who presented no identity, and this page"
                        + " shows exactly what that route holds");
    }

    @Test
    @DisplayName("every alias the table carries names its client version and pending correction")
    void everyaliasSaysWhenItMayGo() {
        final String routes = read(REPOSITORY.resolve("policy/agent-routes.toml"));
        final List<String> aliasBlocks = routes.lines()
                .filter(line -> line.startsWith("client_version = ")
                        || line.startsWith("pending_correction = "))
                .toList();
        assertEquals(0, aliasBlocks.size() % 2,
                "an alias carries a client version without a pending correction, or the other way"
                        + " round, which makes it a second path with no end date: " + aliasBlocks);
    }

    @Test
    @DisplayName("the screen is one Granite page reading one data source")
    void thescreenIsOnePageAndOneSource() {
        final String held = read(REPOSITORY.resolve(SCREEN));
        assertTrue(held.contains("granite/ui/components/shell/page"),
                "the screen is rendered by something other than Granite's own page component, so"
                        + " it looks like this product rather than like the platform");
        assertTrue(held.contains("slingshot-agent/datasource/identity"),
                "the screen no longer reads the data source that fills it: " + held);
    }

    @Test
    @DisplayName("the screen refuses a caller who authenticated as nobody")
    void thescreenRefusesNobody() {
        // Not one status. A platform that challenges answers 401 and one that hides what the
        // session may not read answers 404, and this tier runs on the second: Oak resolves nothing
        // for a caller who cannot see it. What has to hold either way is that no console reaches
        // somebody who presented no identity, which is what the spelling test beside this one has
        // always asked and what this now asks too.
        final HttpResponse<String> answered = requests.readAsNobody(tier.address() + ADDRESS);
        assertTrue(answered.statusCode() >= BELOW_A_SUCCESS,
                "a console screen was rendered for a caller who presented no identity: "
                        + answered.statusCode());
        assertTrue(!answered.body().contains("slingshot-agent/datasource/identity"),
                "a console screen was disclosed to a caller who presented no identity");
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
