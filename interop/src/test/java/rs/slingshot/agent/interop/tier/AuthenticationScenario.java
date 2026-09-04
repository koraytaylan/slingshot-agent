// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Who this agent answers, on a running instance rather than in a model of one.
 *
 * <p>The byte-identity test is the point. A caller who can tell "there is no such user" from "that
 * is not their password" has been handed a way to find out which names exist, and the difference
 * between the two answers is exactly what tells them.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class AuthenticationScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The one route this build serves, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/capabilities";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

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
    @DisplayName("an authenticated caller is answered and nobody in particular is not")
    void anauthenticatedCallerIsAnswered() {
        assertEquals(200, tier.readAsAuthenticatedUser(ROUTE).statusCode(),
                "a caller the platform established was refused");
        assertNotEquals(200, tier.readAsNobody(ROUTE).statusCode(),
                "a request from nobody in particular was answered");
    }

    @Test
    @DisplayName("nobody at all and somebody the platform rejects get the same answer, byte for byte")
    void thetwoRefusalsAreOneAnswer() {
        final HttpResponse<String> nobody = tier.readAsNobody(ROUTE);
        final HttpResponse<String> rejected = requests.readAsUnknownUser(tier.address() + ROUTE);
        assertEquals(nobody.statusCode(), rejected.statusCode(),
                "the two refusals are told apart by their status");
        assertEquals(nobody.body(), rejected.body(),
                "the two refusals are told apart by their body");
        assertTrue(nobody.body() == null || !nobody.body().contains("command_contracts"),
                "a refusal disclosed the document it refused to answer: " + nobody.body());
        assertTrue(nobody.body() == null || nobody.body().isEmpty(),
                "a refusal carried a body at all, and the platform's own error page names the"
                        + " servlet that refused and traces the request: " + nobody.body());
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
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
