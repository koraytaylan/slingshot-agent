// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * The first scenario: install it, ask it what it is, and believe the running instance rather than
 * the build.
 *
 * <p>Everything else in this repository is a claim about software running inside somebody else's
 * Adobe Experience Manager deployment, and none of that is provable from a unit test. This is the
 * first commit where the sentence has a running instance behind it.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class WalkingSkeletonScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route this commit serves, spelled by the committed table and by nothing here. */
    private static final String CAPABILITIES = "/bin/slingshot/agent/capabilities";

    /** The digest the client's own repository records for the transport contract it speaks. */
    private static final String SIBLING_TRANSPORT_DIGEST =
            "295fc1bdf0b88ecb5cbd45898d9a29d0dae1bada76d6c6fced1e99e7cdb2b9f8";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

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
    @DisplayName("the Sling-only bundle is active with every import resolved")
    void theSlingOnlyBundleIsActive() {
        assertEquals(Optional.of("Active"), tier.bundleState(PublicSlingTier.CORE_BUNDLE),
                "the bundle is not active, which means one of its imports is not provided here");
    }

    @Test
    @DisplayName("the Adobe bundle is absent rather than installed and unresolved")
    void theAdobeBundleIsAbsent() {
        assertEquals(Optional.empty(), tier.bundleState(PublicSlingTier.ADOBE_BUNDLE),
                "the Adobe bundle is on this tier, so a failure here could be mistaken for a"
                        + " missing Adobe interface");
    }

    @Test
    @DisplayName("the capability document from the running instance is the one the unit suite proved")
    void theCapabilityDocumentIsTheOneTheUnitSuiteProved() {
        final HttpResponse<String> answered = tier.readAsAuthenticatedUser(CAPABILITIES);
        assertEquals(200, answered.statusCode(), answered.body());
        assertTrue(answered.body().contains("\"command_contracts\":[]"), answered.body());
        assertTrue(answered.body().contains("\"agent_event_store_generation\":1"), answered.body());
        assertTrue(answered.body().contains("\"continuation_authority_ready\":false"),
                answered.body());
        assertTrue(answered.body().contains("\"transport_contract_digest\":\""
                        + SIBLING_TRANSPORT_DIGEST + "\""),
                answered.body());
    }

    @Test
    @DisplayName("the running instance is addressable and what it wrote was captured")
    void theRunningInstanceIsAddressableAndCaptured() {
        assertTrue(tier.address().startsWith("http://localhost:"), tier.address());
        assertEquals(PublicSlingTier.NAME, tier.name());
        assertTrue(!((PublicSlingTier) tier).capturedOutput().isEmpty(),
                "the instance wrote nothing at all, which a starting runtime does not do");
    }

    @Test
    @DisplayName("the same route refuses a request nobody in particular made, disclosing nothing")
    void theSameRouteRefusesAnUnauthenticatedRequest() {
        final HttpResponse<String> refused = tier.readAsNobody(CAPABILITIES);
        assertTrue(refused.statusCode() >= 400, "a request nobody made was answered: "
                + refused.statusCode());
        assertFalse(refused.body().contains("transport_contract_digest"),
                "a refusal disclosed a capability field");
        assertFalse(refused.body().contains("command_contracts"),
                "a refusal disclosed a capability field");
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
