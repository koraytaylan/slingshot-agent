// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

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
import rs.slingshot.agent.interop.tier.SharedPublicSlingTier;
import rs.slingshot.agent.interop.tier.TierRequests;

/**
 * Two nodes whose clocks disagree, and every decision that compares two instants.
 *
 * <p>The failure mode of a skewed clock is two nodes both believing they hold one lease, and the
 * property is always the same shape and never about accuracy: a decision may be conservative and
 * may never be wrong. Waiting longer than the instant says costs somebody a few seconds; deciding
 * earlier costs them a second effect, a stranded token, or an answer that was collected while they
 * were still reading it.</p>
 *
 * <p>The relation that makes a rotation safe is checked here rather than assumed: a key kept for
 * less than the longest token's lifetime plus the declared skew is a key that gets dropped while a
 * token it signed is still valid, and that caller's next page is refused for a reason they can
 * neither see nor fix.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ClockChaosScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String NODE_IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The pinned document store image, which is what makes two nodes one repository. */
    private static final String STORE_IMAGE = "docker.io/library/mongo:7";

    /** The port a node answers on inside its own container. */
    private static final int NODE_PORT = 8080;

    /** The run mode that puts a node's repository in the shared document store. */
    private static final String SHARED_REPOSITORY_MODE = "oak_mongo";

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    /** The status a runtime answers with while it is still starting. */
    private static final int SERVICE_UNAVAILABLE = 500;

    private final TierRequests requests = TierRequests.open();

    private ClusterHarness cluster;

    private ClusterHarness.Cluster nodes;

    @BeforeAll
    void startTwoNodesAgainstOneRepository() {
        // Three containers of its own, so the shared single-node runtime other scenarios keep
        // alive is given back first. A cluster competing with it for the machine is a cluster
        // whose startup reports the load rather than the product.
        SharedPublicSlingTier.release();
        cluster = ClusterHarness.at(REPOSITORY);
        final ClusterHarness.Outcome outcome = cluster.start(STORE_IMAGE, NODE_IMAGE, NODE_PORT,
                List.of(SHARED_REPOSITORY_MODE), List.of(), this::serving);
        nodes = assertInstanceOf(ClusterHarness.Started.class, outcome,
                "the cluster did not come up: " + outcome).cluster();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (cluster != null && nodes != null) {
            cluster.stop(nodes);
        }
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "the cluster left a container running");
    }

    @Test
    @DisplayName("every instant comparison is enumerated, each with the way it may safely be wrong")
    void everycomparisonNamesItsSafeDirection() {
        assertEquals(ClockDisruptor.Comparison.values().length,
                java.util.Arrays.stream(ClockDisruptor.Comparison.values())
                        .map(ClockDisruptor.Comparison::spelling).distinct().count(),
                "two comparisons are spelled the same way");
        assertEquals(ClockDisruptor.Conservative.DECIDE_LATE,
                ClockDisruptor.Comparison.THE_LEASE.conservative(),
                "deciding a lease early was called conservative, and an early lease is two workers"
                        + " writing at once");
        assertEquals(ClockDisruptor.Conservative.DECIDE_EARLY,
                ClockDisruptor.Comparison.TOKEN_EXPIRY.conservative(),
                "honouring a token late was called conservative, and a token honoured after its"
                        + " key is gone is a token nothing can validate");
    }

    @Test
    @DisplayName("every disruption is applied to every comparison, rather than to a chosen few")
    void everydisruptionMeetsEveryComparison() {
        assertEquals(ClockDisruptor.Comparison.values().length
                        * ClockDisruptor.Disruption.values().length,
                ClockDisruptor.everyDisruption().size(),
                "the disruptions are no longer the cross product, and a backward jump against"
                        + " retention is exactly the pair nobody would choose");
        assertTrue(ClockDisruptor.everyDisruption().stream()
                        .anyMatch(pair -> pair.startsWith(
                                ClockDisruptor.Disruption.JUMPED_BACKWARD.spelling())),
                "a backward jump is not among them, and it is the one nobody plans for");
    }

    @Test
    @DisplayName("the key ring keeps a rotated-out key longer than a token plus the declared skew")
    void thepriorRetentionCoversTheSkew() {
        final String contract = read(REPOSITORY.resolve("support/agent-contract.toml"));
        final long retention = valueOf(contract, "continuation_key_prior_retention_milliseconds");
        final long lifetime = valueOf(contract, "continuation_token_lifetime_milliseconds");
        final long skew = valueOf(contract, "clock_skew_allowance_milliseconds");
        assertTrue(ClockDisruptor.priorRetentionCoversTheSkew(retention, lifetime, skew),
                "a rotated-out key is kept for " + retention + " while a token lives " + lifetime
                        + " and clocks may differ by " + skew + ", so a rotation strands a token"
                        + " whose caller can neither see why nor fix it");
    }

    @Test
    @DisplayName("both nodes answer, so a disagreement between their clocks is about them")
    void bothnodesAnswer() {
        assertTrue(requests.readAsAuthenticatedUser(nodes.first().address() + "/.json")
                        .statusCode() < BAD_REQUEST);
        assertTrue(requests.readAsAuthenticatedUser(nodes.second().address() + "/.json")
                        .statusCode() < BAD_REQUEST);
    }

    /**
     * One bound's value, read from the committed contract rather than written down again.
     *
     * @param contract the contract's text
     * @param name the bound
     * @return its value
     */
    private static long valueOf(String contract, String name) {
        return contract.lines()
                .filter(line -> line.startsWith(name + " = "))
                .map(line -> Long.parseLong(line.substring(line.indexOf('=') + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not a bound the contract states"));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private boolean serving(ContainerHandle handle) {
        return requests.respondsBelow(handle.address() + "/system/console/bundles.json",
                        BAD_REQUEST)
                // Answering, not merely not erroring. A node that is still resolving nothing
                // returns 404 here, which is under a server error and satisfied this while the
                // node could not yet serve a caller - so readiness was reached before the thing
                // readiness is for, and whichever test asked first failed instead.
                && requests.respondsBelow(handle.address() + "/.json", BAD_REQUEST);
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
