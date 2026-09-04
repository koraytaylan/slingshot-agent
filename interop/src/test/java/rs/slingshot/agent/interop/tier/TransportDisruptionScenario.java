// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.harness.NetworkDisruptor;

/**
 * A request that left and an answer that did not come back, at every point that can happen.
 *
 * <p>Success and refusal are the easy cases. The one that matters is the severance, because that is
 * where a caller cannot tell whether the work ran — and a caller that guessed either way would be
 * wrong half the time. Every point is severed at the network boundary with a reset rather than a
 * close, so nobody gets an orderly ending they can flush through and read to the end of.</p>
 *
 * <p>Where a severance lands depends on what the route had to say. On an instance where nothing
 * has been submitted, the stream, transfer and intake routes answer without a body, so a severance
 * "part way through" lands in the answer's head rather than in a body there is none of. Proving a
 * mid-body severance on those three needs a served stream and a published artifact, which needs a
 * registered command; this plan's status records that rather than a row here claiming otherwise.
 * </p>
 *
 * <p>What this proves on this side: the instance survives every severance, keeps answering, and is
 * left holding nothing — no route stops working, no room is left occupied, and the same request
 * over a clean connection afterwards produces the same answer it produced before. What the client
 * concluded is the other half, and it needs the client tier and a registered command; this plan's
 * status records that rather than a row here claiming otherwise.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class TransportDisruptionScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** How long a severed request is waited on before the suite decides nothing came back. */
    private static final int SEVERED_SECONDS = 5;

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    private final HttpClient client = HttpClient.newBuilder()
            // The disruptor severs at byte positions in an exchange, and an exchange it can read
            // is one written in the protocol this product speaks over cleartext.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(SEVERED_SECONDS))
            .build();

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
    @DisplayName("every enumerated point severs, and nobody on either side gets an answer")
    void everyenumeratedPointSevers() {
        for (final NetworkDisruptor.Point point : NetworkDisruptor.Point.values()) {
            assertEquals(Answer.NOTHING_CAME_BACK, severed(point),
                    point.spelling() + " produced an answer, so nothing was severed there");
        }
        assertEquals(ENUMERATED, NetworkDisruptor.Point.values().length,
                "a disruption point was added or lost without this suite being told");
        final String declared = harnessValues();
        for (final NetworkDisruptor.Point point : NetworkDisruptor.Point.values()) {
            assertTrue(declared.contains("\"" + point.spelling() + "\""),
                    point.spelling() + " is severed here and the harness's own values do not"
                            + " enumerate it");
        }
    }

    /** How many points there are, which is the list this suite was written against. */
    private static final int ENUMERATED = 8;

    /** What a caller was left with. */
    private enum Answer {
        /** An answer arrived. */
        SOMETHING_CAME_BACK,
        /** Nothing did, which is what a severed connection leaves. */
        NOTHING_CAME_BACK
    }

    @Test
    @DisplayName("the instance survives every severance and answers the same afterwards")
    void theinstanceSurvivesEverySeverance() {
        final String before = String.valueOf(requests.readAsAuthenticatedUser(
                tier.address() + CAPABILITIES).body());
        for (final NetworkDisruptor.Point point : NetworkDisruptor.Point.values()) {
            severed(point);
            final var answered = requests.readAsAuthenticatedUser(tier.address() + CAPABILITIES);
            assertEquals(SERVED, answered.statusCode(),
                    "the instance stopped answering after " + point.spelling());
            assertEquals(before, answered.body(),
                    "the answer changed after " + point.spelling() + ", so a severed request left"
                            + " something behind");
        }
    }

    /** The route every severance is checked against afterwards, because it says the most. */
    private static final String CAPABILITIES = "/bin/slingshot/agent/capabilities";

    /** What a served request is answered with. */
    private static final int SERVED = 200;

    @Test
    @DisplayName("a severed stream leaves room for another, so no room was left occupied")
    void aseveredStreamLeavesRoomForAnother() {
        final List<Integer> answered = new ArrayList<>();
        for (int attempt = 0; attempt < STREAMS; attempt = attempt + 1) {
            severed(NetworkDisruptor.Point.MID_STREAM);
            answered.add(requests.readAsAuthenticatedUser(tier.address() + EVENTS
                    + "?daemon_subscription_identifier=a-subscription-nobody-took"
                    + "&agent_operation_identifier=" + AN_IDENTIFIER).statusCode());
        }
        assertTrue(answered.stream().noneMatch(status -> status == AT_CAPACITY),
                "a severed stream left its room occupied: " + answered);
        assertTrue(answered.stream().allMatch(status -> status == NOTHING_HERE),
                "a stream after a severance was answered with something else: " + answered);
    }

    /** How many streams are severed in a row, which is more than one so a leak would show. */
    private static final int STREAMS = 3;

    /** The route a stream is asked for on. */
    private static final String EVENTS = "/bin/slingshot/agent/events";

    /** What a stream this instance has no room for would be answered with. */
    private static final int AT_CAPACITY = 503;

    /** What a stream on a subscription nobody took is answered with. */
    private static final int NOTHING_HERE = 404;

    @Test
    @DisplayName("a severed transfer and a severed intake leave the route answering as before")
    void aseveredTransferAndIntakeLeaveTheRouteAnswering() {
        severed(NetworkDisruptor.Point.MID_ARTIFACT_TRANSFER);
        assertEquals(NOTHING_HERE, requests.readAsAuthenticatedUser(tier.address() + ARTIFACT
                        + "?agent_operation_identifier=" + AN_IDENTIFIER
                        + "&artifact_slot=result").statusCode(),
                "a severed transfer left the route answering something else");
        severed(NetworkDisruptor.Point.MID_INTAKE);
        assertEquals(NOTHING_HERE, requests.postAsAuthenticatedUser(tier.address() + ARTIFACT
                        + "?agent_operation_identifier=" + AN_IDENTIFIER
                        + "&artifact_slot=result", "bytes for work nobody declared",
                        "application/octet-stream").statusCode(),
                "a severed intake left a slot in a state the next attempt cannot use");
    }

    /** The route an artifact leaves and arrives on. */
    private static final String ARTIFACT = "/bin/slingshot/agent/artifact";

    private Answer severed(NetworkDisruptor.Point point) {
        try (NetworkDisruptor disruptor =
                     NetworkDisruptor.inFrontOf(tier.address(), point)) {
            final Answer answer = through(disruptor.address(), point);
            assertEquals(NetworkDisruptor.Severance.SEVERED, disruptor.severance(),
                    point.spelling() + " never severed anything");
            return answer;
        }
    }

    private Answer through(String address, NetworkDisruptor.Point point) {
        try {
            final HttpResponse<String> answered = client.send(request(address, point),
                    HttpResponse.BodyHandlers.ofString());
            return answered.statusCode() > 0 ? Answer.SOMETHING_CAME_BACK
                    : Answer.NOTHING_CAME_BACK;
        } catch (final java.io.IOException severed) {
            return Answer.NOTHING_CAME_BACK;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Answer.NOTHING_CAME_BACK;
        }
    }

    private HttpRequest request(String address, NetworkDisruptor.Point point) {
        final String asked = address + pathFor(point) + "?agent_operation_identifier="
                + AN_IDENTIFIER + "&artifact_slot=result"
                + "&daemon_subscription_identifier=a-subscription-nobody-took";
        final HttpRequest.Builder building = HttpRequest.newBuilder(URI.create(asked))
                .timeout(Duration.ofSeconds(SEVERED_SECONDS))
                .header("Authorization", "Basic YWRtaW46YWRtaW4=");
        return point == NetworkDisruptor.Point.MID_INTAKE
                ? building.header("Content-Type", "application/octet-stream")
                        .header("Referer", asked)
                        .POST(HttpRequest.BodyPublishers.ofString("bytes nobody declared")).build()
                : building.GET().build();
    }

    private static String pathFor(NetworkDisruptor.Point point) {
        return switch (point) {
            case MID_STREAM -> EVENTS;
            case MID_ARTIFACT_TRANSFER, MID_INTAKE -> ARTIFACT;
            default -> CAPABILITIES;
        };
    }

    private static String harnessValues() {
        try {
            return java.nio.file.Files.readString(
                    REPOSITORY.resolve("support/interop-harness.toml"),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException("the harness values are not readable",
                    unreadable);
        }
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
