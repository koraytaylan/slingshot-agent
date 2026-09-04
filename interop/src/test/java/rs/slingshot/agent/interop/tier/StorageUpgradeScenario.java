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

/**
 * The previous release, populated, and this one installed over it.
 *
 * <p>A fresh install proves nothing about an upgrade, and an upgrade is what every real deployment
 * does. What is proved here is the part no unit suite reaches: that a store written by one release
 * is readable by the next, record for record, with the same values.</p>
 *
 * <p>Before the first release there is nothing to upgrade from. This says so rather than passing,
 * because a suite that quietly passed on that would be reporting an upgrade it never performed —
 * which is exactly the report somebody would act on.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class StorageUpgradeScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        // Its own runtime, because this scenario changes the instance itself. The shared one is
        // given back first: two published runtimes competing for the machine is how a start that
        // takes twenty seconds stops finishing inside ninety.
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
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("with no previous release pinned, this says so rather than passing")
    void anabsentPreviousReleaseIsReportedRatherThanSkipped() {
        final UpgradeTier.Previous previous = UpgradeTier.previous(REPOSITORY);
        if (previous instanceof final UpgradeTier.NothingToUpgradeFrom nothing) {
            assertTrue(!nothing.detail().isBlank(),
                    "there is nothing to upgrade from and nothing said why, which reads as a suite"
                            + " that ran and found nothing wrong");
            return;
        }
        final UpgradeTier.Pinned pinned = (UpgradeTier.Pinned) previous;
        assertTrue(!pinned.version().isBlank() && !pinned.artifactDigest().isBlank(),
                "a release is pinned by a version with no digest, which pins a name rather than"
                        + " some bytes");
    }

    @Test
    @DisplayName("every kind of record the store holds is populated before the upgrade")
    void everykindIsPopulated() {
        final List<String> kinds = UpgradeTier.populatedKinds(REPOSITORY);
        assertTrue(kinds.size() >= 8,
                "fewer kinds are populated than the store holds, and the kind nobody populates is"
                        + " the kind whose upgrade nobody proves: " + kinds);
        assertTrue(kinds.stream().anyMatch(kind -> kind.contains("intake")),
                "no partly-filled intake is populated, and it is the one shape that spans two"
                        + " requests and the one an upgrade is most likely to lose");
        assertTrue(kinds.stream().anyMatch(kind -> kind.contains("key ring")),
                "no key ring is populated, so nothing would notice a token stranded by an upgrade");
    }

    @Test
    @DisplayName("the store an upgrade would read is the one the deployment initialises")
    void thestoreIsTheDeploymentsOwn() {
        final String initialisation = read(REPOSITORY.resolve("ui.config/src/main/content/jcr_root"
                + "/apps/slingshot-agent/osgiconfig/config"
                + "/org.apache.sling.jcr.repoinit.RepositoryInitializer~slingshot-agent.cfg.json"));
        assertTrue(initialisation.contains("/var/slingshot-agent"),
                "the deployment initialises no store, so an upgrade would be proved over nothing");
    }

    @Test
    @DisplayName("the instance this release installs into still refuses a caller who is nobody")
    void theinstanceStillRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode());
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
