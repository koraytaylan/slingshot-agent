// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What the job system did with one operation's attempts, asked for on a running instance.
 *
 * <p>The route answers out of the operation's own outbox and never asks the queue anything, so on
 * an instance holding no operations there is nothing to answer — and what is proved here is that
 * the nothing it answers with discloses nothing either: no queue, no topic, no address.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PhysicalJobScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario asks for, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/jobs";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** An identifier this build reads, which no operation on a fresh instance has. */
    private static final String AN_IDENTIFIER =
            "4ccf24ff283335286ae2d809ae6aff5d994b5cfcb5c9f8e260a32777254de2f8";

    /** What a request from nobody in particular is answered with. */
    private static final int UNAUTHORIZED = 401;

    /** What a lookup nobody can read is answered with. */
    private static final int NOT_YET = 404;

    /** What a request this build cannot read at all is answered with. */
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
    @DisplayName("the route is registered and refuses a lookup from nobody in particular")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHORIZED, requests.readAsNobody(tier.address() + ROUTE
                        + "?agent_operation_identifier=" + AN_IDENTIFIER).statusCode(),
                "a lookup from nobody in particular was answered");
    }

    @Test
    @DisplayName("an operation nobody here holds answers as one belonging to somebody else")
    void anunknownOperationAnswersAsAforeignOne() {
        final var answered = requests.readAsAuthenticatedUser(tier.address() + ROUTE
                + "?agent_operation_identifier=" + AN_IDENTIFIER);
        assertEquals(NOT_YET, answered.statusCode(), answered.body());
        assertTrue(answered.body() == null || answered.body().isEmpty(),
                "a refusal carried a body: " + answered.body());
    }

    @Test
    @DisplayName("no answer this route gives names anything about the instance's own queues")
    void noanswerNamesTheInstancesQueues() {
        final var answered = requests.readAsAuthenticatedUser(tier.address() + ROUTE
                + "?agent_operation_identifier=" + AN_IDENTIFIER);
        for (final String disclosing : List.of("queue", "topic", "rs/slingshot/agent/command")) {
            assertTrue(answered.body() == null || !answered.body().contains(disclosing),
                    "an answer disclosed " + disclosing + ": " + answered.body());
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
