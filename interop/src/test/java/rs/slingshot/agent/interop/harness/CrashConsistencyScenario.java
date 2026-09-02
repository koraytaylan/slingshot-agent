// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.tier.TierRequests;

/**
 * What survives a node being ended the way a machine ends one.
 *
 * <p>A graceful stop proves that the tidying works. This one sends the signal nothing can handle,
 * so what is readable afterwards is what the store already held rather than what a shutdown hook
 * managed to flush on the way out — which is the only arrangement in which "this survives a crash"
 * means anything.</p>
 *
 * <p>It is proved across two nodes sharing one document store rather than by restarting the node
 * that died, and that is a finding rather than a convenience: the pinned public image does not come
 * back to a serving state after an ungraceful kill, because the runtime's own model layer fails to
 * weave classes out of a bundle cache that was mid-write when the process ended. A customer's
 * author is a cluster, where the durable state is in the store and a node is a thing that can be
 * replaced — so this asks the surviving node what the store holds, which is the question the
 * client's own recovery path asks.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CrashConsistencyScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String NODE_IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The pinned document store image, which is what makes two nodes one repository. */
    private static final String STORE_IMAGE = "docker.io/library/mongo:7";

    /** The port a node answers on inside its own container. */
    private static final int NODE_PORT = 8080;

    /** The run mode that puts a node's repository in the shared document store. */
    private static final String SHARED_REPOSITORY_MODE = "oak_mongo";

    /** What the platform answers when it has created something. */
    private static final int CREATED = 201;

    /** What it answers when it is serving a request the way a caller's would be served. */
    private static final int OK = 200;

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    /** The first status that is the runtime failing rather than refusing. */
    private static final int SERVICE_UNAVAILABLE = 500;

    /** A write that changes nothing, which is how readiness is proved without leaving anything. */
    private static final List<String> CHANGES_NOTHING =
            List.of(":operation", "nop", ":nopstatus", "200");

    /** How long the store is given to make one node's write visible to the other. */
    private static final long SETTLE_MILLISECONDS = 5000;

    private final TierRequests requests = TierRequests.open();

    private ClusterHarness cluster;

    private ClusterHarness.Cluster nodes;

    private CrashInjector injector;

    @BeforeAll
    void startTwoNodesAgainstOneStore() {
        cluster = ClusterHarness.at(REPOSITORY);
        injector = CrashInjector.alongside(cluster.harness());
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
                "the scenario left a container running");
    }

    @Test
    @DisplayName("what one node committed is still there after that node is killed outright")
    void acommittedWriteSurvivesAnungracefulKill() throws InterruptedException {
        final String path = "/var/crash-" + System.nanoTime();
        assertEquals(CREATED, requests.submit(address(nodes.first()) + path,
                        List.of("jcr:primaryType", "nt:unstructured", "held", "before the crash"))
                .statusCode(), "the write this scenario is about was not committed");
        settle();
        final CrashInjector.Ended ended = injector.kill(nodes.first(),
                CrashInjector.Point.AFTER_TERMINAL_BEFORE_ACKNOWLEDGEMENT);
        assertEquals(CrashInjector.KILLED, ended.exitStatus(),
                "the node was not killed by the signal nothing can handle");
        assertEquals(CrashInjector.Graceful.NOTHING_WAS_FLUSHED, ended.graceful());
        assertEquals(CrashInjector.Point.AFTER_TERMINAL_BEFORE_ACKNOWLEDGEMENT, ended.point());
        assertFalse(injector.isRunning(nodes.first()), "the node is still running after a kill");
        final var read = requests.readAsAuthenticatedUser(address(nodes.second()) + path + ".json");
        assertEquals(OK, read.statusCode(),
                "what was committed before the kill is not in the store afterwards");
        assertTrue(read.body().contains("before the crash"),
                "what came back is not what was written: " + read.body());
        assertTrue(serving(nodes.second()),
                "the node that was not killed stopped serving when the other one died");
        assertFalse(requests.respondsBelow(address(nodes.first()) + "/.json", SERVICE_UNAVAILABLE),
                "the node that was killed is still answering, so it was not killed");
    }

    @Test
    @DisplayName("every crash point this plan enumerates is named, and no two share a spelling")
    void everycrashPointIsNamed() {
        final List<String> spellings = Arrays.stream(CrashInjector.Point.values())
                .map(CrashInjector.Point::spelling)
                .toList();
        assertEquals(spellings.size(), spellings.stream().distinct().count(),
                "two crash points are spelled the same, so a report cannot say which one broke");
        assertEquals(7, spellings.size(), "a crash point was added or lost");
        for (final CrashInjector.Point point : CrashInjector.Point.values()) {
            assertEquals(point, CrashInjector.Point.named(point.spelling()).orElseThrow(),
                    point + " is not the point its own spelling names");
        }
        assertTrue(CrashInjector.Point.named("somewhere_in_the_middle").isEmpty());
    }

    private void settle() throws InterruptedException {
        // A document store makes one node's commit visible to another on its own background read.
        // Waiting for that is waiting for the store rather than for the product.
        Thread.sleep(SETTLE_MILLISECONDS);
    }

    private static String address(ContainerHandle handle) {
        return handle.address();
    }

    private boolean serving(ContainerHandle handle) {
        return requests.respondsBelow(handle.address() + "/system/console/bundles", BAD_REQUEST)
                && requests.respondsBelow(handle.address() + "/.json", SERVICE_UNAVAILABLE)
                && requests.submitRespondsBelow(handle.address() + "/var", CHANGES_NOTHING,
                        SERVICE_UNAVAILABLE);
    }

    private static Path repositoryRoot() {
        Path walked = Path.of("").toAbsolutePath();
        while (walked != null && !Files.exists(walked.resolve("policy"))) {
            walked = walked.getParent();
        }
        return java.util.Objects.requireNonNull(walked, "this suite is not inside the repository");
    }
}
