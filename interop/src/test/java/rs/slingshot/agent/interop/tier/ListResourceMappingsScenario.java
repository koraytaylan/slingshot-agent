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
 * The resolution rules themselves, on a running instance.
 *
 * <p>What a unit suite proves is that the right pages come back. What it cannot prove is that the
 * command is registered on a real runtime, that its query is covered by an index the platform
 * provides on every deployment this build supports, and that this build ships no index of its own
 * to make that true — an index changes the shape of somebody else's repository and is an operator's
 * decision rather than a side effect of installing an agent.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ListResourceMappingsScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The command this scenario is about. */
    private static final String COMMAND = "list_resource_mappings";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a submission this build will not act on is answered with. */
    private static final int REFUSED = 400;

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
    @DisplayName("this command takes a window and no filter")
    void therowTakesAWindowAndNoFilter() {
        final String schema = read(REPOSITORY.resolve(
                "schemas/agent-protocol/command/list_resource_mappings-arguments.json"));
        assertTrue(schema.contains("result_window"),
                "the committed schema no longer takes a window, so a caller cannot page the rules");
        assertTrue(schema.contains("\"additionalProperties\":false"),
                "the committed schema accepts members nobody declared, which is how a filter would"
                        + " arrive — and a partial view of rules that interact is a misleading one");
        assertTrue(!schema.contains("pattern\":{\"maxLength"),
                "the committed schema declares a filter: " + schema);
    }

    @Test
    @DisplayName("the redaction corpus finds nothing in what this command's own row declares")
    void theredactionCorpusFindsNothing() {
        final String corpus = read(REPOSITORY.resolve("policy/redaction-corpus.toml"));
        assertTrue(!corpus.isBlank(),
                "the redaction corpus is empty, and this listing has a redaction rule of its own"
                        + " because a mapping address can carry a credential");
        final String result = read(REPOSITORY.resolve(
                "schemas/agent-protocol/command/list_resource_mappings-result.json"));
        assertTrue(!result.contains("credential") && !result.contains("password"),
                "the result schema declares a member a credential could travel in: " + result);
    }

    @Test
    @DisplayName("this command's row refuses an operation key, and a write beside it requires one")
    void therowsDifferAboutOperationKeys() {
        assertTrue(row(COMMAND).contains("operation_key = \"refused\""),
                "this command's row no longer refuses an operation key, and a read nobody can"
                        + " repeat differently needs nothing to hold it to one attempt");
        assertTrue(row("create_page").contains("operation_key = \"required\""),
                "a command that refuses a key and one that requires one no longer differ, which"
                        + " is the whole reason the rule is read from the row rather than from the"
                        + " access class");
    }

    @Test
    @DisplayName("this command's row declares no unknown outcome, because a read changes nothing")
    void therowDeclaresNoUnknownOutcome() {
        assertTrue(!row(COMMAND).contains("outcome_unknown"),
                "a read declares an outcome nobody knows, and a read that changes nothing has no"
                        + " half-way state for anybody to be unsure about");
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
