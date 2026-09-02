// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

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
import rs.slingshot.agent.interop.tier.TierRequests;

/**
 * A node killed outright while it is holding work, and what the other node does about it.
 *
 * <p>An author on the row this product is built for is not one machine. Work moves, instances stop,
 * and the repository underneath is shared — so every property about contention needs two nodes,
 * because a single node cannot contend with itself in the way that matters and a process racing
 * itself proves the process.</p>
 *
 * <p>The property at every handover point is the same one: exactly one effect for every committed
 * admission, including where it was the surviving node's recovery that delivered it. Waiting while
 * a lease is live costs a few seconds; taking one early costs somebody a second write.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ClusterHandoverScenario {

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

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** How long the nodes are given to agree, which a document store does on an interval. */
    private static final int SETTLE_MILLISECONDS = 5000;

    /**
     * How many settles the surviving node is given to start serving again before that is the
     * failure. A node whose peer was killed recovers a lease the document store still holds, and
     * answers nothing useful while it does.
     */
    private static final int RECOVERY_ATTEMPTS = 24;

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
    @DisplayName("both nodes are one store, proved by writing on one and reading on the other")
    void bothNodesAreOneStore() throws InterruptedException {
        final String path = "/var/handover-" + System.nanoTime();
        assertTrue(requests.submit(nodes.first().address() + path,
                        List.of("jcr:primaryType", "nt:unstructured")).statusCode()
                < BAD_REQUEST, "the first node would not write, so nothing here proves anything");
        Thread.sleep(SETTLE_MILLISECONDS);
        assertTrue(requests.readAsAuthenticatedUser(nodes.second().address() + path + ".json")
                        .statusCode() < BAD_REQUEST,
                "the second node cannot see what the first wrote, so these are two stores and"
                        + " every handover property below would be about nothing");
    }

    @Test
    @DisplayName("the handover points are enumerated rather than whichever one somebody hit")
    void thehandoverPointsAreEnumerated() {
        assertEquals(CrashInjector.Point.values().length,
                java.util.Arrays.stream(CrashInjector.Point.values())
                        .map(CrashInjector.Point::spelling).distinct().count(),
                "two handover points are spelled the same way, so a finding would not say which");
        assertTrue(java.util.Arrays.stream(CrashInjector.Point.values())
                        .anyMatch(point -> point == CrashInjector.Point.DURING_A_SWEEP),
                "a sweep is not among the points, and a node killed part-way through one is the"
                        + " handover most likely to leave a half-collected store");
    }

    @Test
    @DisplayName("a node killed outright is killed rather than asked to stop")
    void anodeIsKilledRatherThanAskedToStop() {
        final CrashInjector.Ended ended = CrashInjector.alongside(ContainerHarness.at(REPOSITORY))
                .kill(nodes.second(), CrashInjector.Point.AFTER_ADMISSION_BEFORE_START);
        assertEquals(CrashInjector.Graceful.NOTHING_WAS_FLUSHED, ended.graceful(),
                "the node was given a chance to tidy up, and a handover that only works after a"
                        + " clean shutdown is a handover that does not work");
        assertEquals(CrashInjector.KILLED, ended.exitStatus(),
                "the node did not exit the way a killed process exits: " + ended);
    }

    @Test
    @DisplayName("the surviving node still answers, and still refuses a caller who is nobody")
    void thesurvivingNodeStillAnswers() throws InterruptedException {
        // Asked once it is serving again rather than the moment its peer died. The survivor is
        // recovering a lease the document store still holds and answers 500 while it does, so
        // asking straight away asks about the recovery — and what this is for is what the survivor
        // answers, which is the same refusal it gave before anybody was killed.
        assertTrue(servingAgain(nodes.first()),
                "the surviving node never started serving again after its peer was killed, which"
                        + " is the handover failing rather than the refusal changing");
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(nodes.first().address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "the surviving node either stopped answering or stopped refusing, and both are"
                        + " worse than the node that was killed");
    }

    private boolean servingAgain(ContainerHandle handle) throws InterruptedException {
        for (int attempt = 0; attempt < RECOVERY_ATTEMPTS && !serving(handle); attempt++) {
            Thread.sleep(SETTLE_MILLISECONDS);
        }
        return serving(handle);
    }

    private boolean serving(ContainerHandle handle) {
        return requests.respondsBelow(handle.address() + "/system/console/bundles.json",
                        BAD_REQUEST)
                && requests.respondsBelow(handle.address() + "/.json", SERVICE_UNAVAILABLE);
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
