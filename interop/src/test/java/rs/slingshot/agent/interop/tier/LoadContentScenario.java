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

/**
 * The first command, on a running instance.
 *
 * <p>What a unit suite proves about a command is that its code is right. What it cannot prove is
 * that the command is <em>there</em> — registered on a real runtime, reachable through the route a
 * caller uses, and holding to the rules its own registry row states rather than the ones its tests
 * restate. That is what this is for, and it is the fact that would otherwise be left for later.</p>
 *
 * <p>The operation-key rule is read from the row here rather than written down again. A test that
 * restated "this command needs a key" would pass on the day somebody changed the row and forgot the
 * test, which is the day it mattered.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LoadContentScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The command this scenario is about. */
    private static final String COMMAND = "load_content_as_json";

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("the row this command ships is the row this scenario is written against")
    void therowIsTheOneThisScenarioAssumes() {
        final String row = read(REPOSITORY.resolve("policy/commands/" + COMMAND + ".toml"));
        assertTrue(row.contains("operation_key = \"required\""),
                "this command's row no longer requires an operation key, so what this scenario"
                        + " goes on to assert about a submission without one is about nothing");
        assertTrue(row.contains("access = \"read\""),
                "this command's row no longer calls it a read");
        assertTrue(row.contains("execution = \"immediate\""),
                "this command's row no longer runs it inside its caller's own request");
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

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a submission this build will not act on is answered with. */
    private static final int REFUSED = 400;

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
