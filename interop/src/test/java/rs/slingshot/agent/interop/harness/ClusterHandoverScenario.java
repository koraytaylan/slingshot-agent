// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import rs.slingshot.agent.interop.tier.PublicSlingTier;
import rs.slingshot.agent.interop.tier.SharedPublicSlingTier;
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
    private static final int RECOVERY_ATTEMPTS = 12;

    /** Where a bundle is handed to a node's platform to install. */
    private static final String BUNDLE_INSTALL_PATH = "/system/console/bundles";

    /** Where a node reports what it has installed. */
    private static final String BUNDLE_STATE_PATH = "/system/console/bundles.json";

    /** The field a platform console takes a bundle under. */
    private static final String BUNDLE_FIELD = "bundlefile";

    /** How many settles a node is given to make the bundle active before that is the failure. */
    private static final int INSTALL_ATTEMPTS = 60;

    private final TierRequests requests = TierRequests.open();

    private ClusterHarness cluster;

    private ClusterHarness.Cluster nodes;

    @BeforeAll
    void startTwoNodesAgainstOneRepository() throws InterruptedException {
        // Three containers of its own, so the shared single-node runtime other scenarios keep
        // alive is given back first. A cluster competing with it for the machine is a cluster
        // whose startup reports the load rather than the product.
        SharedPublicSlingTier.release();
        cluster = ClusterHarness.at(REPOSITORY);
        final ClusterHarness.Outcome outcome = cluster.start(STORE_IMAGE, NODE_IMAGE, NODE_PORT,
                List.of(SHARED_REPOSITORY_MODE), List.of(), this::serving);
        nodes = assertInstanceOf(ClusterHarness.Started.class, outcome,
                "the cluster did not come up: " + outcome).cluster();
        // Here rather than in a second @BeforeAll, because nothing orders two of those and this
        // has to happen once the nodes exist. On both, because what this scenario asks is what the
        // survivor serves, and a node that was never given the bundle serves none of the agent's
        // routes at all: the route would be an address nothing is registered at, the platform's
        // own posting servlet would answer it by trying to create a node there, and the 500 that
        // came back would read exactly like the agent having broken.
        installTheAgent(nodes.first());
        installTheAgent(nodes.second());
    }

    private void installTheAgent(ContainerHandle node) throws InterruptedException {
        final HttpResponse<String> handed = requests.upload(node.address() + BUNDLE_INSTALL_PATH,
                List.of("action", "install", "bundlestart", "start"), BUNDLE_FIELD, builtBundle());
        assertTrue(handed.statusCode() < BAD_REQUEST,
                node.address() + " refused the bundle with " + handed.statusCode());
        for (int attempt = 0; attempt < INSTALL_ATTEMPTS && !carriesTheAgent(node); attempt++) {
            Thread.sleep(SETTLE_MILLISECONDS);
        }
        assertTrue(carriesTheAgent(node), PublicSlingTier.CORE_BUNDLE + " never became active on "
                + node.address() + ", so nothing there serves the agent's own routes");
    }

    private boolean carriesTheAgent(ContainerHandle node) {
        return PublicSlingTier.stateOf(requests.readAsAuthenticatedUser(
                        node.address() + BUNDLE_STATE_PATH).body(), PublicSlingTier.CORE_BUNDLE)
                .filter("Active"::equals)
                .isPresent();
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
        // Serving again is the platform being back; the agent's own route follows a moment after
        // it, and the minute below is for that gap. It is a gap and not a broken node - which is
        // worth saying, because until both nodes were given the bundle this address had nothing
        // registered at it, the platform's own posting servlet answered by trying to create a
        // node there, and the 500 it returned read exactly like a handover that had failed.
        assertEquals(UNAUTHENTICATED, refusalOnceRecovered(nodes.first()),
                "the surviving node either stopped answering or stopped refusing, and both are"
                        + " worse than the node that was killed");
    }

    private int refusalOnceRecovered(ContainerHandle handle) throws InterruptedException {
        int answered = 0;
        for (int attempt = 0; attempt < RECOVERY_ATTEMPTS; attempt++) {
            answered = requests.postAsNobody(handle.address() + SUBMIT, "{}", "application/json")
                    .statusCode();
            if (answered == UNAUTHENTICATED) {
                return answered;
            }
            Thread.sleep(SETTLE_MILLISECONDS);
        }
        return answered;
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
