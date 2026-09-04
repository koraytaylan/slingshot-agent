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
 * The console following a running operation, on a running instance.
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
final class LiveTailScenario {

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
    @DisplayName("the console follows an operation on the very route a client follows it on")
    void thetailUsesTheClientsOwnRoute() {
        final String script = read(REPOSITORY.resolve(
                "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/clientlibs/console"
                        + "/js/console.js"));
        assertTrue(script.contains("/bin/slingshot/agent/events"),
                "the console follows its own stream rather than the one a client follows, so the"
                        + " two could show different accounts of the same operation");
        assertTrue(script.contains("source.close()"),
                "the tail is never closed, and a stream nobody is watching is a connection the"
                        + " instance is holding open for nobody — an author has a bounded number");
        assertTrue(!script.contains("import ") && !script.contains("require("),
                "the one script this product ships pulls in something else: " + script);
    }

    @Test
    @DisplayName("the event route refuses a caller who authenticated as nobody")
    void theeventRouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.readAsNobody(tier.address() + "/bin/slingshot/agent/events")
                        .statusCode(),
                "a caller who presented no identity opened an event stream");
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
                    // Neither of the archives a release also builds: a javadoc jar handed to the
                    // platform as a bundle is refused with a 500 that reads like the product
                    // failing to install.
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources")
                            && !String.valueOf(file.getFileName()).contains("javadoc"))
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
