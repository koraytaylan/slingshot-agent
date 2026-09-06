// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.interop.tier.SharedPublicSlingTier;
import rs.slingshot.agent.interop.tier.TierRequests;

/** Generation publication and retained records survive losing the writer after its first save. */
final class GenerationRotationCrashScenario {

    @Test
    void theSurvivorReadsRetainedWorkAfterTheFirstSaveWriterIsKilled()
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
            final var ended = proof.verifyGenerationCrash(CrashInjector.alongside(harness.harness()));
            assertEquals(CrashInjector.KILLED, ended.exitStatus());
            assertEquals(CrashInjector.Graceful.NOTHING_WAS_FLUSHED, ended.graceful());
            assertTrue(serving(requests, nodes.second()), "the surviving node stopped serving");
            harness.harness().stop(nodes.second());
            final var replacement = harness.harness().start("localhost/slingshot-agent-public-sling:1",
                    8080, List.of("SLINGSHOT_DOCUMENT_STORE=document-store:27017",
                            "MONGODB_HOST=document-store", "MONGODB_PORT=27017"),
                    List.of("oak_mongo"), new ContainerHarness.Attachment(nodes.network(), "replacement"),
                    node -> serving(requests, node));
            final var restarted = assertInstanceOf(ContainerHarness.Started.class, replacement,
                    "the replacement runtime did not start: " + replacement).handle();
            try {
                proof.verifyGenerationRestart(repository, restarted);
            } finally {
                harness.harness().stop(restarted);
            }
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
