// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A caller without repository grants cannot read or alter the provisioned state tree.
 *
 * <p>The instance administrator seeds and verifies the fixtures. Original caller content access
 * and explicit state readers' agent-route refusals are exercised by StateAccessOwnershipScenario.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class PrivilegeEscalationScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    private static final String STATE = "/var/slingshot-agent";

    private static final String KEY_RING = STATE + "/key-ring";

    private static final String CURRENT_CANARY = "proof-current-key-material";

    private static final String PRIOR_CANARY = "proof-prior-key-material";

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        SharedPublicSlingTier.release();
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
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("an ungranted caller cannot read the provisioned state through any tested spelling")
    void theagentsOwnTreeIsUnreachable() {
        assertTrue(requests.readAsAuthenticatedUser(tier.address() + STATE + ".json")
                .statusCode() < BAD_REQUEST, "the state fixture must exist before checking denial");
        assertUnreachable(STATE);
    }

    @Test
    @DisplayName("an ungranted caller cannot read or alter populated key-ring storage")
    void thekeyRingIsUnreachable() {
        assertTrue(requests.submit(tier.address() + KEY_RING,
                List.of("jcr:primaryType", "nt:unstructured", "current", CURRENT_CANARY,
                        "prior", PRIOR_CANARY)).statusCode() < BAD_REQUEST);
        final String before = requests.readAsAuthenticatedUser(
                tier.address() + KEY_RING + ".json").body();
        assertTrue(before.contains(CURRENT_CANARY) && before.contains(PRIOR_CANARY));
        assertUnreachable(KEY_RING);
        assertTrue(requests.postAsUnpermittedUser(tier.address() + KEY_RING,
                "current=changed", "application/x-www-form-urlencoded").statusCode() >= BAD_REQUEST);
        assertEquals(before, requests.readAsAuthenticatedUser(
                tier.address() + KEY_RING + ".json").body());
    }

    private void assertUnreachable(String path) {
        final List<String> reached = new ArrayList<>();
        for (final String spelling : spellingsOf(path)) {
            final var answer = requests.readAsUnpermittedUser(tier.address() + spelling);
            if (answer.statusCode() < BAD_REQUEST || answer.body().contains(CURRENT_CANARY)
                    || answer.body().contains(PRIOR_CANARY)) {
                reached.add(spelling);
            }
            assertTrue(requests.readAsNobody(tier.address() + spelling).statusCode() >= BAD_REQUEST);
        }
        assertEquals(List.of(), reached, "ungranted callers reached protected state: " + reached);
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode());
    }

    /**
     * Every spelling of one address a request could take.
     *
     * @param address the canonical address
     * @return the spellings, each of which reaches the same resource if anything does
     */
    private static List<String> spellingsOf(String address) {
        return List.of(address, address + ".json", address + ".infinity.json", address + "/",
                address + ".html", address + ".tidy.json");
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
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
