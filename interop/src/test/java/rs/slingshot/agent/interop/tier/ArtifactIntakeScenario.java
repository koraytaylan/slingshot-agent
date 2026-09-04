// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Taking in a declared payload, asked for on a running instance.
 *
 * <p>On a fresh instance nothing has been submitted, so nothing is waiting for a payload — and that
 * is exactly the case worth proving on a real runtime: bytes arriving for work nobody declared are
 * refused before a byte of them is stored, and the refusal says nothing about what this store does
 * hold.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ArtifactIntakeScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario sends to, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/artifact";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    /** What a request from nobody in particular is answered with. */
    private static final int UNAUTHORIZED = 401;

    /** What a payload nothing is waiting for is answered with. */
    private static final int NOT_WAITED_FOR = 404;

    /** What a request this build cannot read at all is answered with. */
    private static final int REFUSED = 400;

    /** What one payload is, which is bytes rather than a document. */
    private static final String PAYLOAD = "bytes for work nobody declared";

    /** Where this agent keeps things, which no answer may disclose. */
    private static final String THE_AGENTS_OWN_TREE = "/var/slingshot-agent";

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
    @DisplayName("the route is registered and refuses a payload from nobody in particular")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHORIZED, requests.postAsNobody(sending("payload"), PAYLOAD,
                        "application/octet-stream").statusCode(),
                "a payload from nobody in particular was taken");
    }

    @Test
    @DisplayName("a payload nothing is waiting for is refused, and says nothing about the store")
    void apayloadNothingIsWaitingForIsRefused() {
        final var answered = requests.postAsAuthenticatedUser(sending("payload"), PAYLOAD,
                "application/octet-stream");
        assertEquals(NOT_WAITED_FOR, answered.statusCode(), answered.body());
        assertFalse(String.valueOf(answered.body()).contains(THE_AGENTS_OWN_TREE),
                "a refusal disclosed where this agent keeps things: " + answered.body());
        assertTrue(answered.body() == null || answered.body().isEmpty(),
                "a refusal carried a body: " + answered.body());
    }

    @Test
    @DisplayName("a payload naming nothing this build reads is refused rather than guessed at")
    void apayloadNamingNothingReadableIsRefused() {
        assertEquals(REFUSED, requests.postAsAuthenticatedUser(tier.address() + ROUTE, PAYLOAD,
                        "application/octet-stream").statusCode(),
                "a payload naming no operation and no slot was taken");
        assertEquals(REFUSED, requests.postAsAuthenticatedUser(tier.address() + ROUTE
                        + "?agent_operation_identifier=not-an-identifier&artifact_slot=payload",
                        PAYLOAD, "application/octet-stream").statusCode(),
                "a payload naming an operation this build cannot read was taken");
    }

    private String sending(String slot) {
        return tier.address() + ROUTE + "?agent_operation_identifier=" + AN_IDENTIFIER
                + "&artifact_slot=" + slot;
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
