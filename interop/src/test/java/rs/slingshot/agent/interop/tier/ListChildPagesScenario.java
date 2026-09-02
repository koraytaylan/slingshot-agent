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
 * The operation an operator performs most, on a running instance.
 *
 * <p>What a unit suite proves is that the iteration is right. What it cannot prove is that the
 * command is registered on a real runtime, and that it stays a repository read rather than becoming
 * a search — which is checked here against the declared-query inventory on the instance's own
 * committed policy, because the day this command starts issuing a query is the day it needs an
 * index on somebody's deployment to stay fast.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ListChildPagesScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The command this scenario is about. */
    private static final String COMMAND = "list_child_pages";

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
    @DisplayName("this command's row refuses an operation key, which the client's own table says")
    void therowRefusesAnOperationKey() {
        assertTrue(row(COMMAND).contains("operation_key = \"refused\""),
                "this command's row no longer refuses an operation key, so what this scenario"
                        + " asserts about a submission carrying one is about nothing");
    }

    @Test
    @DisplayName("this command issues no query, so it needs no index on anybody's deployment")
    void thiscommandIssuesNoQuery() {
        assertTrue(!read(REPOSITORY.resolve("policy/query-index-coverage.toml"))
                        .contains("issued_by = \"" + COMMAND + "\""),
                "this command now declares a query. Listing what is directly under a known node is"
                        + " something the repository answers by looking, and turning the most common"
                        + " operation an operator performs into a search makes it depend on an"
                        + " index somebody has to maintain.");
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity");
    }

    @Test
    @DisplayName("a submission this build cannot read is refused before anything runs")
    void asubmissionThisBuildCannotReadIsRefused() {
        assertEquals(REFUSED, requests.postAsAuthenticatedUser(tier.address() + SUBMIT,
                        "{\"command_wire_name\":\"" + COMMAND + "\"}", "application/json")
                .statusCode(),
                "a submission carrying nothing but a name was accepted");
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
