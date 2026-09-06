// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.interop.tier.SharedPublicSlingTier;
import rs.slingshot.agent.interop.tier.TierRequests;

/** A killed intake writer leaves either a reusable declaration or complete, accounted bytes. */
final class IntakePublicationCrashScenario {

    @Test
    void killingTheWriterBeforePublicationLeavesAReusablePromise()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        killedPublication(false);
    }

    @Test
    void killingTheWriterAfterPublicationPreservesTheCompletedSlot()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        killedPublication(true);
    }

    private void killedPublication(boolean committed)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final Path repository = Path.of(System.getProperty("slingshot.repository.root")).toAbsolutePath();
        SharedPublicSlingTier.release();
        final TierRequests requests = TierRequests.open();
        final ClusterHarness harness = ClusterHarness.at(repository);
        final var outcome = harness.start("docker.io/library/mongo:7",
                "localhost/slingshot-agent-public-sling:1", 8080, List.of("oak_mongo"), List.of(),
                node -> serving(requests, node));
        final var nodes = assertInstanceOf(ClusterHarness.Started.class, outcome,
                "the shared repository cluster did not start: " + outcome).cluster();
        try {
            final var proof = new ExclusiveTransitionRuntime(repository, requests, nodes);
            final var ended = proof.verifyIntakeCrash(CrashInjector.alongside(harness.harness()), committed);
            assertEquals(CrashInjector.KILLED, ended.exitStatus());
            assertEquals(CrashInjector.Graceful.NOTHING_WAS_FLUSHED, ended.graceful());
        } finally {
            harness.stop(nodes);
        }
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(repository));
    }

    private static boolean serving(TierRequests requests, ContainerHandle node) {
        return requests.respondsBelow(node.address() + "/system/console/bundles", 400)
                && requests.respondsBelow(node.address() + "/.json", 400)
                && requests.submitRespondsBelow(node.address() + "/var",
                        List.of(":operation", "nop", ":nopstatus", "200"), 500);
    }
}
