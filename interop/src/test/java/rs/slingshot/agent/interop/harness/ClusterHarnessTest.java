// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two nodes against one shared document store.
 *
 * <p>This arrangement exists from the first commit rather than as a later refinement, because an
 * author on the row this product is built for <em>is</em> a cluster: a lease two workers race for, a
 * count two nodes advance, a key ring two nodes rotate are all properties a single instance cannot
 * exhibit at all. A harness that could only start one would let every one of them pass here and
 * fail on a customer's author.</p>
 *
 * <p>What this suite proves is the arrangement: one store, two separately addressable nodes both
 * pointed at it, and nothing left behind. That two nodes see one another's writes is a property of
 * a repository rather than of a harness, and it is proved on the tier that runs one.</p>
 */
final class ClusterHarnessTest {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned document store two nodes run against. */
    private static final String STORE_IMAGE = "docker.io/library/mongo:7";

    /** The pinned image this harness proves itself against. */
    private static final String NODE_IMAGE = "docker.io/library/alpine:3";

    /** A port a node would answer on, which the probe publishes and nothing listens on. */
    private static final int NODE_PORT = 8080;

    private final ClusterHarness cluster = ClusterHarness.at(REPOSITORY);

    @AfterEach
    void nothingIsLeftBehind() {
        assertEquals(List.of(), cluster.harness().leaked(),
                "this suite left a container running that it started");
    }

    @Test
    @DisplayName("one store and two nodes start, each node separately addressable")
    void twoNodesStartAgainstOneStore() {
        final ClusterHarness.Outcome outcome = cluster.start(STORE_IMAGE, NODE_IMAGE, NODE_PORT,
                List.of("sleep", "600"), handle -> true);
        final ClusterHarness.Cluster started = assertInstanceOf(ClusterHarness.Started.class,
                outcome, "the cluster was refused: " + outcome).cluster();
        assertNotEquals(started.first().identifier(), started.second().identifier(),
                "both nodes are the same container");
        assertNotEquals(started.first().mappedPort(), started.second().mappedPort(),
                "both nodes answer on the same address, so neither can be acted on alone");
        assertTrue(started.store().mappedPort() > 0,
                "the shared document store published no address for the nodes to reach");
        cluster.stop(started);
        assertEquals(List.of(), cluster.harness().leaked(),
                "stopping the cluster left something running");
    }

    @Test
    @DisplayName("a store that cannot start refuses the cluster and leaves nothing behind")
    void aStoreThatCannotStartRefusesTheCluster() {
        final ClusterHarness.Outcome outcome = cluster.start(
                "docker.invalid/nothing/like-this:0", NODE_IMAGE, NODE_PORT, handle -> true);
        final ClusterHarness.Refused refused = assertInstanceOf(ClusterHarness.Refused.class,
                outcome, "a cluster started without its shared store");
        assertEquals(ContainerHarness.Failure.IMAGE_ABSENT, refused.failure());
        assertTrue(refused.detail().contains("the shared document store"), refused.detail());
    }

    @Test
    @DisplayName("a node that cannot start stops the store rather than leaving it running")
    void aNodeThatCannotStartStopsTheStore() {
        final ClusterHarness.Outcome outcome = cluster.start(STORE_IMAGE,
                "docker.invalid/nothing/like-this:0", NODE_PORT, List.of("sleep", "600"),
                handle -> true);
        final ClusterHarness.Refused refused = assertInstanceOf(ClusterHarness.Refused.class,
                outcome, "a cluster started without its nodes");
        assertTrue(refused.detail().contains("the first node"), refused.detail());
        assertEquals(List.of(), cluster.harness().leaked(),
                "the store was left running after the node it was started for could not be");
    }

    @Test
    @DisplayName("the cluster is started through the same wrapper a single instance is")
    void theClusterUsesTheSameWrapper() {
        assertEquals("podman", cluster.harness().engine());
        assertEquals(Duration.ofMillis(300_000), cluster.harness().readinessDeadline());
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
