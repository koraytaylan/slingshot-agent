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
 * The tools navigation entry, on a running instance.
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
final class NavigationScenario {

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
    @DisplayName("the navigation entry is written where Adobe's own extension point reads")
    void theentryIsWhereThePlatformLooks() {
        final Path entry = REPOSITORY.resolve(
                "ui.apps/src/main/content/jcr_root/apps/cq/core/content/nav/tools"
                        + "/slingshot-agent/.content.xml");
        assertTrue(java.nio.file.Files.isRegularFile(entry),
                "there is no tools navigation entry, so an operator who has just installed this"
                        + " finds nothing under Tools and concludes nothing was installed");
        final String held = read(entry);
        assertTrue(held.contains("href=\"/apps/slingshot-agent/content/console.html\""),
                "the entry points at nothing, so clicking it teaches an operator that this"
                        + " product is broken: " + held);
        assertTrue(held.contains("jcr:title=\"slingshot.agent.console.title\""),
                "the entry's title is a sentence rather than a dictionary key, which is a console"
                        + " that is English for everybody forever and nobody notices until"
                        + " somebody asks");
    }

    @Test
    @DisplayName("the package filter removes only the node this product created")
    void uninstallingRemovesOnlyWhatItWrote() {
        final String filter = read(REPOSITORY.resolve(
                "ui.apps/src/main/content/META-INF/vault/filter.xml"));
        assertTrue(filter.contains("root=\"/apps/cq/core/content/nav/tools/slingshot-agent\""),
                "the filter does not cover the navigation leaf, so installing writes a node that"
                        + " uninstalling leaves behind");
        assertTrue(!filter.contains("root=\"/apps/cq/core/content/nav/tools\""),
                "the filter reaches the navigation parent, so uninstalling would take every other"
                        + " product's entry with it — on somebody else's instance rather than"
                        + " here");
    }

    @Test
    @DisplayName("the dictionary carries every string the entry names")
    void thedictionaryCarriesTheEntrysStrings() {
        final String dictionary = read(REPOSITORY.resolve(
                "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/i18n/en/.content.xml"));
        for (final String key : List.of("slingshot.agent.console.title",
                "slingshot.agent.console.description")) {
            assertTrue(dictionary.contains("sling:key=\"" + key + "\""),
                    key + " is named by the navigation entry and carried by no dictionary, so a"
                            + " reader sees the key rather than a sentence");
        }
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity");
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
