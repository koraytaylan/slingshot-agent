// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The stream route on a running instance, where the interesting answers are the refusals.
 *
 * <p>What a running instance adds to the unit suite is that these refusals are ordinary responses
 * that arrive and end. A client that never got a stream should not have to parse one to find out
 * why, and a refusal that held the connection open would be indistinguishable from a stream with
 * nothing to say — on an author, that is a request thread held for a subscriber who was refused.
 * </p>
 *
 * <p>What cannot be arranged here is a stream that is actually served: nothing this product exposes
 * takes a subscription over the wire, so on a fresh instance there is no subscription for a stream
 * to follow. That half is proved in the unit suite against a real repository, and the recorded
 * exception in this plan's status says so rather than a row here claiming otherwise.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class EventStreamScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario asks for, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/events";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    /** What a request from nobody in particular is answered with. */
    private static final int UNAUTHORIZED = 401;

    /** What a stream on something nobody here holds is answered with. */
    private static final int UNKNOWN = 404;

    /** What a request this build cannot read at all is answered with. */
    private static final int REFUSED = 400;

    /** What the media type of a stream is, which a refusal must not carry. */
    private static final String STREAM = "text/event-stream";

    /** How long a refusal may take before it is a connection somebody is holding open. */
    private static final Duration PROMPTLY = Duration.ofSeconds(10);

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
    @DisplayName("the route is registered and refuses a request from nobody in particular")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHORIZED, requests.readAsNobody(asking("following-daemon-one"))
                        .statusCode(),
                "a stream was opened for nobody in particular");
    }

    @Test
    @DisplayName("a subscription nobody took is refused as an ordinary response, not as a stream")
    void asubscriptionNobodyTookIsRefusedAsAnOrdinaryResponse() {
        final Instant asked = Instant.now();
        final var answered = requests.readAsAuthenticatedUser(asking("a-subscription-nobody-took"));
        assertEquals(UNKNOWN, answered.statusCode(), answered.body());
        assertFalse(answered.headers().firstValue("content-type").orElse("").contains(STREAM),
                "a refusal opened a stream to say it would not open one");
        assertTrue(answered.body() == null || answered.body().isEmpty(),
                "a refusal carried a body: " + answered.body());
        assertTrue(Duration.between(asked, Instant.now()).compareTo(PROMPTLY) < 0,
                "a refusal held the connection open, which on an author is a request thread held"
                        + " for a subscriber that was refused");
    }

    @Test
    @DisplayName("an ask this build cannot read is refused rather than guessed at")
    void anaskThisBuildCannotReadIsRefused() {
        assertEquals(REFUSED, requests.readAsAuthenticatedUser(tier.address() + ROUTE
                        + "?daemon_subscription_identifier=a%20name%20with%20spaces"
                        + "&agent_operation_identifier=not-an-identifier").statusCode(),
                "an ask this build cannot read was guessed at");
        assertEquals(REFUSED, requests.readAsAuthenticatedUser(tier.address() + ROUTE)
                        .statusCode(),
                "an ask naming nothing at all was guessed at");
    }

    @Test
    @DisplayName("a stream naming an incarnation this store does not serve is told which one it is")
    void astreamNamingAforeignIncarnationIsToldWhichOneIsServed() {
        assertEquals(RESET, requests.readAsAuthenticatedUser(asking("following-daemon-one")
                        + "&agent_event_store_generation=7").statusCode(),
                "a cursor into an incarnation this store does not serve was read as a position");
    }

    /** What a stream into an incarnation this store does not serve is answered with. */
    private static final int RESET = 409;

    @Test
    @DisplayName("no other spelling of the route reaches it, and no other method does either")
    void nootherSpellingOfTheRouteReachesIt() {
        for (final String spelling : List.of(ROUTE + ".json", ROUTE + "/", ROUTE + "/anything",
                ROUTE + ".html")) {
            final int answered = requests.readAsAuthenticatedUser(tier.address() + spelling
                    + "?daemon_subscription_identifier=following-daemon-one").statusCode();
            assertTrue(answered == UNKNOWN || answered == METHOD_REFUSED,
                    spelling + " reached the route, and a route with spellings nobody enumerated"
                            + " is a route whose policy applies to some of the ways in: "
                            + answered);
        }
        assertEquals(METHOD_REFUSED, requests.postAsAuthenticatedUser(tier.address() + ROUTE, "",
                        "application/json").statusCode(),
                "the stream route answered a method the table does not give it");
    }

    /** What a method the table does not give a route is answered with. */
    private static final int METHOD_REFUSED = 405;

    private String asking(String subscription) {
        return tier.address() + ROUTE + "?daemon_subscription_identifier=" + subscription
                + "&agent_operation_identifier=" + AN_IDENTIFIER;
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
