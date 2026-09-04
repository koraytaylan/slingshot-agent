// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Whether Sling's own resolution reaches past the check that says a route has one spelling.
 *
 * <p>The unit suite proves the rule. This proves the thing the rule is about: that a request with a
 * selector, an extension, a suffix, or a trailing segment really does arrive at the same servlet on
 * a running instance, and really is refused there. A rule about a platform's behaviour that is only
 * ever tested against a model of that platform is a rule about the model.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RouteReachabilityScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The one route this build serves, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/capabilities";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a request at a spelling this agent does not serve is answered with. */
    private static final int NOT_FOUND = 404;

    /** What a request using a method this route does not answer is answered with. */
    private static final int METHOD_NOT_ALLOWED = 405;

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
    @DisplayName("the exact path is answered")
    void theexactPathIsAnswered() {
        assertEquals(200, tier.readAsAuthenticatedUser(ROUTE).statusCode(),
                "the route does not answer the path the table gives it");
    }

    @Test
    @DisplayName("every other spelling of the path is refused on a running instance")
    void everyotherSpellingIsRefused() {
        for (final String spelling : List.of(ROUTE + ".json", ROUTE + ".detail.json",
                ROUTE + "/anything", ROUTE + "/", ROUTE.toUpperCase(Locale.ROOT))) {
            final HttpResponse<String> answered = tier.readAsAuthenticatedUser(spelling);
            assertEquals(NOT_FOUND, answered.statusCode(),
                    spelling + " was answered rather than refused: " + answered.body());
            assertTrue(answered.body() == null || !answered.body().contains("command_contracts"),
                    spelling + " disclosed the document it should not have answered");
        }
    }

    @Test
    @DisplayName("a method this route does not answer is refused on a running instance")
    void amethodTheRouteDoesNotAnswerIsRefused() {
        assertEquals(METHOD_NOT_ALLOWED, requests.submit(tier.address() + ROUTE,
                        List.of("anything", "at-all")).statusCode(),
                "this route answered a method the table does not give it");
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = java.nio.file.Files.list(target)) {
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
