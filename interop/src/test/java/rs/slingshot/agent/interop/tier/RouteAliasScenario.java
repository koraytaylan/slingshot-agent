// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What a customer actually receives, which is the canonical routes and nothing else.
 *
 * <p>The client's old spellings are declared and shipped off. A unit suite can prove the switch
 * works; only an instance running the packages this build produces can prove what the shipped
 * configuration does with it — that no alias path answers, and that the canonical routes still do.
 * </p>
 *
 * <p>The distinction matters because of where the aliases sit. `/libs` is a namespace a customer's
 * dispatcher and content delivery network are frequently configured to pass more freely than
 * anything else, so an authenticated state-changing route arriving there by default would be a
 * wider surface than this agent asked for — and one nobody chose.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RouteAliasScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** Every spelling the client's production code asks for, which this build ships off. */
    private static final List<String> ALIASES = List.of(
            "/libs/slingshot/agent/operations",
            "/libs/slingshot/agent/jobs",
            "/libs/slingshot/agent/subscriptions/high-water",
            "/libs/slingshot/agent/events",
            "/libs/slingshot/agent/artifacts");

    /** The canonical route this scenario proves is still answering. */
    private static final String CANONICAL = "/bin/slingshot/agent/capabilities";

    /** What a path nobody serves is answered with. */
    private static final int NOTHING_THERE = 404;

    /** What a caller nobody authenticated is answered with. */
    private static final int UNAUTHORIZED = 401;

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
    @DisplayName("no alias answers on an instance carrying what this build ships")
    void noaliasAnswersOnAshippedInstance() {
        for (final String alias : ALIASES) {
            final int answered = requests.readAsAuthenticatedUser(tier.address() + alias)
                    .statusCode();
            assertEquals(NOTHING_THERE, answered,
                    alias + " answered " + answered + " on an instance that never turned the"
                            + " client's old spellings on");
        }
    }

    @Test
    @DisplayName("an alias is not merely refused: nothing this agent serves is registered there")
    void analiasIsNotMerelyRefused() {
        for (final String alias : ALIASES) {
            final var answered = requests.readAsNobody(tier.address() + alias);
            assertNotEquals(UNAUTHORIZED, answered.statusCode(),
                    alias + " refused an unauthenticated caller, which is a servlet of this"
                            + " product answering there rather than nothing being there at all");
        }
    }

    @Test
    @DisplayName("the canonical routes still answer, so nothing above was proved by an empty build")
    void thecanonicalRoutesStillAnswer() {
        assertEquals(UNAUTHORIZED, requests.readAsNobody(tier.address() + CANONICAL).statusCode(),
                "the canonical route does not answer either, so this instance proves nothing");
        assertTrue(requests.readAsAuthenticatedUser(tier.address() + CANONICAL).statusCode() < 400,
                "the canonical route refuses an authenticated caller");
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
