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

/**
 * What the sweep will take and whether taking it is enough, on a running instance.
 *
 * <p>Capacity says how full a store is now; retention says when things leave. An operator who can
 * see one without the other cannot answer the only question that matters, which is whether it will
 * still be full tomorrow.</p>
 *
 * <p>What a unit suite settles is the arithmetic. What it cannot settle is that the amount the page
 * calls releasable is the amount a sweep on a real instance actually releases, and that every bound
 * the page shows is the one the contract this build authenticated states.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RetentionViewScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** Where the screen this scenario is about is declared. */
    private static final String SCREEN = "ui.apps/src/main/content/jcr_root/apps/slingshot-agent"
            + "/content/console/retention/.content.xml";

    /** Where an operator reaches that screen. */
    private static final String ADDRESS =
            "/apps/slingshot-agent/content/console/retention.html";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

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
    @DisplayName("the sweep this page describes is the one this deployment is configured to run")
    void thesweepIsTheConfiguredOne() {
        final String configured = read(REPOSITORY.resolve("ui.config/src/main/content/jcr_root"
                + "/apps/slingshot-agent/osgiconfig/config/org.apache.sling.commons.scheduler.impl"
                + ".SchedulerServiceFactory~slingshot-agent-sweep.cfg.json"));
        assertTrue(!configured.isBlank(),
                "the deployment configures no sweep, so what this page calls releasable is what"
                        + " nothing would ever release");
    }

    @Test
    @DisplayName("every bound this page shows is one the contract states rather than one written here")
    void everyboundIsFromTheContract() {
        final String contract = read(REPOSITORY.resolve("support/agent-contract.toml"));
        assertTrue(contract.contains("maximum_current_generation_event_rows"),
                "the contract no longer states the bound this page shows for events, and a page"
                        + " with its own copy would go on showing the old one");
        assertTrue(contract.contains("maintenance_sweep_work_bound_rows"),
                "the contract no longer bounds what one sweep examines, which is what makes the"
                        + " releasable amount something a sweep can actually reach");
    }

    @Test
    @DisplayName("the screen is one Granite page reading one data source")
    void thescreenIsOnePageAndOneSource() {
        final String held = read(REPOSITORY.resolve(SCREEN));
        assertTrue(held.contains("granite/ui/components/shell/page"),
                "the screen is rendered by something other than Granite's own page component, so"
                        + " it looks like this product rather than like the platform");
        assertTrue(held.contains("slingshot-agent/datasource/retention"),
                "the screen no longer reads the data source that fills it: " + held);
    }

    @Test
    @DisplayName("the screen refuses a caller who authenticated as nobody")
    void thescreenRefusesNobody() {
        assertEquals(UNAUTHENTICATED, requests.readAsNobody(tier.address() + ADDRESS).statusCode(),
                "a console screen was rendered for a caller who presented no identity");
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
