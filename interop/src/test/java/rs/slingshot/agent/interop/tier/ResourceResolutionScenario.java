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
 * Both directions of resource mapping, on a running instance.
 *
 * <p>What a unit suite proves is that the right pages come back. What it cannot prove is that the
 * command is registered on a real runtime, that its query is covered by an index the platform
 * provides on every deployment this build supports, and that this build ships no index of its own
 * to make that true — an index changes the shape of somebody else's repository and is an operator's
 * decision rather than a side effect of installing an agent.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ResourceResolutionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The command this scenario is about. */
    private static final String COMMAND = "resolve_resource_path";

    /** The other direction of the same machinery, which keeps its own row. */
    private static final String OTHER = "map_resource_path";

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
    @DisplayName("the two rows differ by exactly the request address only one direction takes")
    void thetworowsDifferByOneThing() {
        final String resolve = row(COMMAND);
        final String map = row(OTHER);
        assertTrue(resolve.contains("request_address_rejected"),
                "the resolving direction no longer declares the failure only it can produce");
        assertTrue(!map.contains("request_address_rejected"),
                "the mapping direction declares a failure it cannot produce: mapping does not"
                        + " depend on the request it happens under, so it can never refuse one");
        assertTrue(resolve.contains("operation_key = \"refused\"")
                        && map.contains("operation_key = \"refused\""),
                "one of the two directions no longer refuses an operation key");
        assertTrue(resolve.contains("result_bytes = 262144")
                        && map.contains("result_bytes = 262144"),
                "the two directions no longer answer under the same bound");
    }

    @Test
    @DisplayName("the committed argument schemas differ by the request address and nothing else")
    void theschemasDifferByTheRequestAddress() {
        final String resolve = read(REPOSITORY.resolve(
                "schemas/agent-protocol/command/resolve_resource_path-arguments.json"));
        final String map = read(REPOSITORY.resolve(
                "schemas/agent-protocol/command/map_resource_path-arguments.json"));
        assertTrue(resolve.contains("request_address"),
                "the resolving direction's schema no longer requires the request it resolves under");
        assertTrue(!map.contains("request_address"),
                "the mapping direction's schema declares a request address, and a caller supplying"
                        + " one would believe something untrue about the answer they get: " + map);
    }

    @Test
    @DisplayName("this build ships no index definition, because an index is an operator's decision")
    void thisbuildShipsNoIndexDefinition() {
        assertTrue(!installed().contains("oak:index"),
                "this build ships an index definition. An index lives outside /apps, changes the"
                        + " shape of somebody else's repository, and is an operator's decision"
                        + " rather than a side effect of installing an agent.");
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

    private static String installed() {
        final java.nio.file.Path packages = REPOSITORY.resolve("ui.apps/src/main/content");
        if (!Files.isDirectory(packages)) {
            return "";
        }
        try (var entries = Files.walk(packages)) {
            return entries.map(entry -> String.valueOf(entry.getFileName()))
                    .reduce("", (all, one) -> all + " " + one);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
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
