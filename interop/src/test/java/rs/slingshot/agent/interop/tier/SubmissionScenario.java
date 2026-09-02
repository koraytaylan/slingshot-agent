// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
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
 * The route that starts work, asked for on a running instance.
 *
 * <p>What a running instance adds to the unit suite is everything between a client and a servlet:
 * that the route is registered at all, that the platform's own authentication reaches it, that a
 * body arrives as bytes rather than as parameters, and that a refusal comes back as a status with
 * nothing in it. What it cannot add yet is a command: this build registers none, so a well-formed
 * submission is refused for naming work nothing here runs — which is itself the property that a
 * record is never written for work nobody will do.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class SubmissionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario asks for, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a submission this side will not consider is answered with. */
    private static final int REFUSED = 400;

    /** What a request from nobody in particular is answered with. */
    private static final int UNAUTHORIZED = 401;

    /** What a request in a media type this route does not take is answered with. */
    private static final int UNSUPPORTED_MEDIA_TYPE = 415;

    /** What a caller nobody permitted is answered with. */
    private static final int FORBIDDEN = 403;

    /**
     * The group a fresh install permits, which an operator widens by naming further ones.
     *
     * <p>Nobody is put in it here. The public tier has no user-manager surface — a request to one
     * falls through to the platform's own write servlet, which answers that it cannot create
     * {@code userManager} under {@code /system} — so what a running instance can show is the
     * refusal. That a member is admitted is proved in the unit suite against a real repository's
     * own user manager, and on a licensed tier where an administrator is already in this group.</p>
     */
    private static final String PERMITTED_GROUP = "administrators";

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
    @DisplayName("the route is registered and refuses a submission from nobody in particular")
    void therouteIsRegisteredAndRefusesNobody() {
        final HttpResponse<String> anonymous = requests.postAsNobody(tier.address() + ROUTE,
                submission(), "application/json");
        assertEquals(UNAUTHORIZED, anonymous.statusCode(),
                "a submission from nobody in particular was not refused: " + anonymous.body());
        assertTrue(anonymous.body() == null || anonymous.body().isEmpty(),
                "a refusal carried a body: " + anonymous.body());
    }

    @Test
    @DisplayName("an authenticated caller outside every permitted group may not start work")
    void anauthenticatedCallerOutsideEveryGroupIsRefused() {
        assertEquals(FORBIDDEN, requests.postAsUnpermittedUser(tier.address() + ROUTE,
                        submission(), "application/json").statusCode(),
                "a caller the operator has not permitted started work, where the only permitted"
                        + " group is " + PERMITTED_GROUP + " and this caller is in none");
    }

    @Test
    @DisplayName("authorization is decided before a body is looked at")
    void authorizationIsDecidedBeforeAbodyIsLookedAt() {
        assertEquals(FORBIDDEN, requests.postAsUnpermittedUser(tier.address() + ROUTE,
                        "this is not a document at all", "application/json").statusCode(),
                "a body was read for a caller who was about to be refused");
    }

    @Test
    @DisplayName("a submission in a media type this route does not take is refused before it is read")
    void asubmissionInAnotherMediaTypeIsRefused() {
        assertEquals(UNSUPPORTED_MEDIA_TYPE, requests.postAsAuthenticatedUser(
                        tier.address() + ROUTE, submission(), "text/plain").statusCode(),
                "a body in a media type this route does not take was read anyway");
    }

    private static String submission() {
        try {
            return Files.readString(REPOSITORY.resolve("core/src/test/resources/fixtures/"
                            + "submit-servlet/a-submission.json"), StandardCharsets.UTF_8)
                    .replaceAll("\"request_start_unix_milliseconds\": [0-9]+",
                            "\"request_start_unix_milliseconds\": " + System.currentTimeMillis());
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
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
