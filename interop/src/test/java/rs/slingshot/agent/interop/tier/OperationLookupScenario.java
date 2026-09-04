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
 * What one operation has become, asked for on a running instance.
 *
 * <p>On an instance holding no operations, every well-formed lookup is the "not there yet" answer —
 * which is the one this route must give rather than "never there", because a client waits on one
 * and gives up on the other. What a running instance adds is that the distinction survives the
 * platform: the route is registered, the answer carries a hint rather than a body, and an
 * identifier this build cannot read is refused rather than waited on.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class OperationLookupScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this scenario asks for, spelled by the committed table and by nothing here. */
    private static final String ROUTE = "/bin/slingshot/agent/snapshot";

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
    @DisplayName("an operation this instance has never held is not yet, with a hint to wait")
    void anoperationNobodyHoldsIsNotYet() {
        final var answered = requests.readAsAuthenticatedUser(tier.address() + ROUTE
                + "?agent_operation_identifier=" + AN_IDENTIFIER);
        assertEquals(NOT_YET, answered.statusCode(), answered.body());
        assertTrue(answered.headers().firstValue("Retry-After").isPresent(),
                "a client was told to wait without being told how long");
        assertTrue(answered.body() == null || answered.body().isEmpty(),
                "a refusal carried a body: " + answered.body());
    }

    @Test
    @DisplayName("an identifier this build does not read is refused rather than waited on")
    void anidentifierThisBuildDoesNotReadIsRefused() {
        assertEquals(REFUSED, requests.readAsAuthenticatedUser(tier.address() + ROUTE
                        + "?agent_operation_identifier=not-an-identifier").statusCode(),
                "a client was told to wait for something this build cannot even read");
        assertEquals(REFUSED, requests.readAsAuthenticatedUser(tier.address() + ROUTE).statusCode(),
                "a lookup naming no operation at all was answered");
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
