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
 * What this agent publishes about itself, against a running instance.
 *
 * <p>Four of the six checks compare this build against itself and a unit suite settles them. The
 * other two are about somebody else's instance: a servlet registers only for a path prefix the
 * resolver has been configured to permit, and a query is cheap only while the index covering it
 * exists in that repository. Both are silent, and both look exactly like something else.</p>
 *
 * <p>So what is proved here is the part a unit suite cannot reach — that the prefix the route table
 * declares is one a real instance actually serves under, that the queries the coverage policy
 * declares are the ones this build asks a real repository to plan, and that the tree the state-tree
 * check compares against is the one the deployment's own initialisation creates rather than a
 * second copy written down here.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class HealthCheckScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** Where the deployment's own repository initialisation is configured. */
    private static final String REPOINIT = "ui.config/src/main/content/jcr_root/apps/"
            + "slingshot-agent/osgiconfig/config/"
            + "org.apache.sling.jcr.repoinit.RepositoryInitializer~slingshot-agent.cfg.json";

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
    @DisplayName("the tree the state-tree check compares against is the one the deployment creates")
    void thestateTreeIsTheOneTheDeploymentCreates() {
        final String initialisation = read(REPOSITORY.resolve(REPOINIT));
        final String layout = read(REPOSITORY.resolve("policy/repository-layout.toml"));
        final String root = declaredRoot(layout);
        assertTrue(!root.isEmpty(), "the layout declares no root, and the check compares against"
                + " nothing: " + layout);
        assertTrue(initialisation.contains(root),
                "the deployment's own initialisation does not create " + root + ", so the tree the"
                        + " check compares against is one this bundle wrote down and nothing"
                        + " creates: " + initialisation);
    }

    @Test
    @DisplayName("the queries the coverage check asks about are the ones the policy declares")
    void thequeriesAreTheDeclaredOnes() {
        final String coverage = read(REPOSITORY.resolve("policy/query-index-coverage.toml"));
        assertTrue(coverage.contains("[[query]]"),
                "the coverage policy declares no query, and a check with nothing to ask the"
                        + " platform to plan reports covered on a repository with no index at all");
        assertTrue(coverage.contains("[[index]]"),
                "the policy names no index, and the index a query wants is one of the two things an"
                        + " operator types into a search");
        assertTrue(coverage.contains("properties = "),
                "a declared query names no property, so nothing here can say which index would"
                        + " have to cover it");
    }

    @Test
    @DisplayName("the prefix the route table declares is one a running instance serves under")
    void theroutePrefixIsOneAnInstanceServes() {
        final String routes = read(REPOSITORY.resolve("policy/agent-routes.toml"));
        assertTrue(routes.contains("/bin/slingshot/agent"),
                "the route table no longer declares the prefix every route hangs from, which is"
                        + " the thing the registration check names when nothing is registered");
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "a route under the declared prefix answered nothing at all, which is what an"
                        + " instance whose resolver was narrowed looks like");
    }

    /**
     * The root the layout declares, read from the document rather than written down again.
     *
     * @param layout the committed layout
     * @return the root, or the empty string where the document declares none
     */
    private static String declaredRoot(String layout) {
        return layout.lines()
                .filter(line -> line.startsWith("root = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElse("");
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
