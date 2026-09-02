// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import rs.slingshot.agent.interop.harness.RepositoryFaultInjector;

/**
 * Four faults at every point a write happens, against a running store.
 *
 * <p>A commit that fails, one that conflicts, one refused because there is no room, and a session
 * that went away mid-operation are four different claims, and the recovery for each is different.
 * The one that matters most is the fourth: it is the only one where this side cannot say whether
 * anything landed, and reporting it as a failure would have a caller retry a write that may already
 * have happened.</p>
 *
 * <p>Injected rather than waited for. Three of the four are rare enough that a suite waiting for
 * them would never run them, and rare is exactly what makes a recovery path the least-exercised
 * code in a product.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RepositoryFaultScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** How many injections this suite runs: four faults at seven points. */
    private static final int EVERY_INJECTION = 28;

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
    @DisplayName("every fault is injected at every point, rather than at the ones somebody chose")
    void everyfaultIsInjectedAtEveryPoint() {
        assertEquals(EVERY_INJECTION, RepositoryFaultInjector.everyInjection().size(),
                "the injections are no longer the cross product, and the ones somebody would leave"
                        + " out are the ones nobody has thought about");
        assertEquals(EVERY_INJECTION, RepositoryFaultInjector.everyInjection().stream()
                        .map(RepositoryFaultInjector.Injection::spelling).distinct().count(),
                "two injections are spelled the same way, so a finding would not say which");
    }

    @Test
    @DisplayName("the four faults stay four answers, with none standing in for another")
    void thefourFaultsAreFourAnswers() {
        assertTrue(RepositoryFaultInjector.dispositionsAreDistinct(),
                "two faults became one answer, which means the recovery for one of them is"
                        + " running for the other");
        assertEquals(RepositoryFaultInjector.Disposition.OUTCOME_UNKNOWN,
                RepositoryFaultInjector.Fault.SESSION_INVALIDATED.disposition(),
                "a session that went away was given an answer that claims something, and it is the"
                        + " one case where nobody knows whether the write landed");
        assertEquals(RepositoryFaultInjector.Disposition.ADMISSION_REFUSED,
                RepositoryFaultInjector.Fault.STORE_IS_FULL.disposition(),
                "a full store was reported as a failed write rather than as an admission that"
                        + " should never have been made");
    }

    @Test
    @DisplayName("the store this suite injects into is the one the deployment initialises")
    void thestoreIsTheDeploymentsOwn() {
        final String initialisation = read(REPOSITORY.resolve("ui.config/src/main/content/jcr_root"
                + "/apps/slingshot-agent/osgiconfig/config"
                + "/org.apache.sling.jcr.repoinit.RepositoryInitializer~slingshot-agent.cfg.json"));
        assertTrue(initialisation.contains("/var/slingshot-agent"),
                "the deployment initialises no store, so what these faults would be injected into"
                        + " is a tree nothing creates");
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity, and every fault here is"
                        + " injected into work that was started properly");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
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
