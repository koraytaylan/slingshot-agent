// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.tier.TierRequests;

/**
 * What claim-by-creation rests on, proved where a single instance cannot exhibit it.
 *
 * <p>Two nodes against one shared document store, both told to create the same path at the same
 * moment. Exactly one creates it and the other is told it is there — which is the whole of the
 * claim primitive's promise, and the reason nothing in this repository holds a lock across a
 * request. A single instance cannot prove it: one process racing itself proves the process.</p>
 *
 * <p>The primitive itself is proved in the unit suite against a real Oak repository. What is proved
 * here is the repository behaviour it is built on, on the arrangement a customer's author actually
 * is — because "the repository will do this" is exactly the kind of assumption that is true until
 * the second node arrives.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ConcurrentWriteScenario {

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

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    /** The status a runtime answers with while it is still starting. */
    private static final int SERVICE_UNAVAILABLE = 500;

    /** How long the two writers are given to meet. */
    private static final int RACE_SECONDS = 60;

    private final TierRequests requests = TierRequests.open();

    private ClusterHarness cluster;

    private ClusterHarness.Cluster nodes;

    @BeforeAll
    void startTwoNodesAgainstOneRepository() {
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
    @DisplayName("two nodes claiming one path at once converge on one claim")
    void oneClaimWins() throws InterruptedException,
            java.util.concurrent.ExecutionException {
        final String path = "/var/claimed-" + System.nanoTime();
        final List<Integer> answered = raced(path);
        assertTrue(answered.stream().noneMatch(status -> status >= SERVICE_UNAVAILABLE),
                "a node failed rather than losing a race: " + answered);
        settle();
        final String first = claimantSeenBy(nodes.first(), path);
        final String second = claimantSeenBy(nodes.second(), path);
        assertEquals(first, second, "the two nodes disagree about who claimed " + path
                + ", so a claim on one node is not a claim on the other");
        assertTrue(WRITERS.contains(first), "the claim carries a writer neither node is: " + first);
    }

    @Test
    @DisplayName("what one node wrote the other reads, because they are one repository")
    void bothNodesSeeOneRepository() throws InterruptedException {
        final String path = "/var/shared-" + System.nanoTime();
        assertEquals(CREATED, requests.submit(address(nodes.first()) + path,
                List.of("jcr:primaryType", "nt:unstructured")).statusCode());
        settle();
        assertEquals(200, requests.readAsAuthenticatedUser(address(nodes.second()) + path
                        + ".json").statusCode(),
                "the second node cannot see what the first wrote, so this is not one repository");
    }

    @Test
    @DisplayName("each node is separately addressable, and neither is the other")
    void eachNodeIsItsOwn() {
        assertTrue(!address(nodes.first()).equals(address(nodes.second())),
                "both nodes answer on one address");
        assertEquals(200, requests.readAsAuthenticatedUser(address(nodes.first()) + "/.json")
                .statusCode());
        assertEquals(200, requests.readAsAuthenticatedUser(address(nodes.second()) + "/.json")
                .statusCode());
    }

    /**
     * Both nodes told to claim one path at the same moment, and what each answered.
     *
     * <p>Each writes its own name, so the claim carries who made it. Two writers that both believed
     * they created the node would leave two different names, and a store that ends with one name is
     * a store that resolved the race rather than one that let both through.</p>
     */
    private List<Integer> raced(String path) throws InterruptedException,
            java.util.concurrent.ExecutionException {
        try (ExecutorService writers = Executors.newFixedThreadPool(WRITERS.size())) {
            final List<Callable<Integer>> both = List.of(
                    () -> requests.submit(address(nodes.first()) + path,
                            List.of("jcr:primaryType", "nt:unstructured", CLAIMANT,
                                    WRITERS.getFirst())).statusCode(),
                    () -> requests.submit(address(nodes.second()) + path,
                            List.of("jcr:primaryType", "nt:unstructured", CLAIMANT,
                                    WRITERS.getLast())).statusCode());
            final List<Future<Integer>> answered = writers.invokeAll(both, RACE_SECONDS,
                    TimeUnit.SECONDS);
            return List.of(answered.getFirst().get(), answered.getLast().get());
        }
    }

    /** The property a claim carries its claimant's own name in. */
    private static final String CLAIMANT = "claimed_by";

    /** The two writers, each of which is one node. */
    private static final List<String> WRITERS = List.of("the-first-node", "the-second-node");

    /** How long the nodes are given to agree, which is a background read rather than a wait. */
    private static final int SETTLE_MILLISECONDS = 5000;

    private static void settle() throws InterruptedException {
        // A document store reads what other nodes wrote on an interval rather than at once, so
        // asking both nodes immediately would be asking before either could answer.
        Thread.sleep(SETTLE_MILLISECONDS);
    }

    private String claimantSeenBy(ContainerHandle node, String path) {
        final String answered = requests.readAsAuthenticatedUser(address(node) + path
                + ".json").body();
        assertTrue(answered.contains(CLAIMANT), node.identifier() + " sees no claim at " + path
                + ": " + answered);
        final int from = answered.indexOf("\"" + CLAIMANT + "\":\"") + CLAIMANT.length() + 4;
        return answered.substring(from, answered.indexOf('"', from));
    }

    private boolean serving(ContainerHandle handle) {
        return requests.respondsBelow(handle.address() + "/system/console/bundles.json",
                        BAD_REQUEST)
                && requests.respondsBelow(handle.address() + "/.json", SERVICE_UNAVAILABLE)
                && requests.submitRespondsBelow(handle.address() + "/",
                        List.of(":operation", "nop", ":nopstatus", "200"), SERVICE_UNAVAILABLE);
    }

    private static String address(ContainerHandle handle) {
        return handle.address();
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
