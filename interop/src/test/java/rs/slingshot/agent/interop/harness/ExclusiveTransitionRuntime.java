// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import rs.slingshot.agent.interop.tier.TierRequests;

/** Drives the built store classes with forced save interleavings on separate DocumentNodeStores. */
final class ExclusiveTransitionRuntime {

    private static final String ENDPOINT = "/bin/slingshot-proof/transitions";
    private static final String EFFECTS = "/var/slingshot-proof/effects/";
    private final TierRequests requests;
    private final ClusterHarness.Cluster nodes;

    ExclusiveTransitionRuntime(Path repository, TierRequests requests, ClusterHarness.Cluster nodes)
            throws IOException, InterruptedException {
        this.requests = requests;
        this.nodes = nodes;
        final Path bundle = bundle(repository);
        for (final ContainerHandle node : List.of(nodes.first(), nodes.second())) {
            install(node, bundle);
        }
    }

    private void install(ContainerHandle node, Path bundle) throws InterruptedException {
        final var installed = requests.upload(node.address() + "/system/console/bundles",
                List.of("action", "install", "bundlestart", "start"), "bundlefile", bundle);
        assertTrue(installed.statusCode() < 400, installed.body());
        await(() -> "ready".equals(requests.readAsAuthenticatedUser(node.address() + ENDPOINT).body()),
                "the test-only store probe did not register");
    }

    void verifyGenerationRestart(Path repository, ContainerHandle restarted)
            throws IOException, InterruptedException {
        install(restarted, bundle(repository));
        assertEquals("retained", post(restarted, "generation-view", "all"),
                "a fresh runtime could not read the generation committed before the writer was killed");
    }

    void verifyRaces() throws InterruptedException, ExecutionException, TimeoutException {
        assertEquals("prepared", post(nodes.first(), "prepare", "all"));
        await(() -> observes(nodes.second(), "admission", "absent")
                && observes(nodes.second(), "start", "ACCEPTED")
                && observes(nodes.second(), "loss", "ACCEPTED")
                && observes(nodes.second(), "counter", "0"),
                "the second node did not observe the prepared shared state");
        race("counter", "cas", "WRITTEN", List.of("VALUE_CHANGED", "CONTENDED"));
        assertEquals("1", post(nodes.first(), "view", "counter"));
        await(() -> observes(nodes.second(), "counter", "1"),
                "the shared counter did not become one");
        race("admission", "admit", "Accepted", List.of("Recognised", "Refused"));
        await(() -> observes(nodes.second(), "admission", "ACCEPTED"),
                "the admitted record did not persist on the shared store");
        race("start", "start", "Held", List.of("Refused"));
        awaitEffects(nodes.second(), "start");
        assertEquals("Recognised/Refused", post(nodes.second(), "resend", "start"));
        assertEffects(nodes.first(), "start");
    }

    CrashInjector.Ended killBeforeReply(CrashInjector injector)
            throws InterruptedException, ExecutionException, TimeoutException {
        final String[] capacity = post(nodes.first(), "capacity-source", "all").split(",");
        await(() -> "2/2/2/1".equals(post(nodes.second(), "capacity-view", "all")),
                "the survivor did not observe the source reservations before process loss");
        final String live = post(nodes.second(), "capacity-live", "all");
        assertEquals("3/3/3/1", post(nodes.second(), "capacity-view", "all"));
        assertEquals("armed", post(nodes.first(), "arm", "loss"));
        try (var callers = Executors.newSingleThreadExecutor()) {
            final var lost = callers.submit(() -> post(nodes.first(), "lose", "loss"));
            awaitPrepared(nodes.first(), "loss", lost);
            awaitEffects(nodes.second(), "loss");
            final CrashInjector.Ended ended = injector.kill(nodes.first(),
                    CrashInjector.Point.AFTER_COMMAND_COMMIT_BEFORE_TERMINAL);
            final ExecutionException unanswered = assertThrows(ExecutionException.class,
                    () -> lost.get(10, TimeUnit.SECONDS),
                    "the side effect's response was received despite killing the blocked node");
            assertInstanceOf(java.io.UncheckedIOException.class, unanswered.getCause(),
                    "the failure must be lost transport, not an assertion about an HTTP response");
            assertEquals("Recognised/Refused", post(nodes.second(), "resend", "loss"));
            assertEffects(nodes.second(), "loss");
            assertEquals("recovered", post(nodes.second(), "capacity-recover", capacity[0]));
            assertEquals("2/2/2/0", post(nodes.second(), "capacity-view", "all"),
                    "counters must equal active reservation vectors after recovering the killed process");
            assertEquals("recovered", post(nodes.second(), "capacity-recover", capacity[0]));
            assertEquals("2/2/2/0", post(nodes.second(), "capacity-view", "all"));
            assertReservation(capacity[1]);
            assertReservation(live);
            final var retained = requests.readAsAuthenticatedUser(nodes.second().address()
                    + "/var/slingshot-agent/proof-retained-capacity.1.json");
            assertEquals(200, retained.statusCode(), retained.body());
            assertTrue(retained.body().contains("\"retained\":true"), retained.body());
            return ended;
        }
    }

    CrashInjector.Ended verifyGenerationCrash(CrashInjector injector)
            throws InterruptedException, ExecutionException, TimeoutException {
        assertEquals("prepared", post(nodes.first(), "prepare", "all"));
        assertEquals("generation-prepared", post(nodes.first(), "generation-prepare", "all"));
        assertEquals("armed", post(nodes.first(), "arm", "rotation-loss"));
        try (var callers = Executors.newSingleThreadExecutor()) {
            final var lost = callers.submit(() -> post(nodes.first(), "generation-lose", "rotation-loss"));
            try {
                awaitPrepared(nodes.first(), "rotation-loss", lost);
                await(() -> "retained".equals(post(nodes.second(), "generation-view", "all")),
                        "the first rotation save did not publish history, retention, and retained work");
                final var ended = injector.kill(nodes.first(),
                        CrashInjector.Point.AFTER_COMMAND_COMMIT_BEFORE_TERMINAL);
                final ExecutionException unanswered = assertThrows(ExecutionException.class,
                        () -> lost.get(10, TimeUnit.SECONDS));
                assertInstanceOf(java.io.UncheckedIOException.class, unanswered.getCause());
                assertEquals("retained", post(nodes.second(), "generation-view", "all"),
                        "retained generation state was lost with the process that committed it");
                return ended;
            } finally {
                if (injector.isRunning(nodes.first())) {
                    post(nodes.first(), "release", "rotation-loss");
                }
            }
        }
    }

    CrashInjector.Ended verifyIntakeCrash(CrashInjector injector, boolean committed)
            throws InterruptedException, ExecutionException, TimeoutException {
        assertEquals("prepared", post(nodes.first(), "prepare", "all"));
        assertEquals("intake-prepared", post(nodes.first(), "intake-prepare", "all"));
        await(() -> "reserved".equals(post(nodes.second(), "intake-view", "all")),
                "the survivor did not observe the intake promise");
        final String action = committed ? "intake-after" : "intake-before";
        assertEquals("armed", post(nodes.first(), "arm", action));
        try (var callers = Executors.newSingleThreadExecutor()) {
            final var lost = callers.submit(() -> post(nodes.first(), action, action));
            try {
                awaitPrepared(nodes.first(), action, lost);
                final String expected = committed ? "complete" : "reserved";
                await(() -> expected.equals(post(nodes.second(), "intake-view", "all")),
                        "the survivor observed neither the intact promise nor the complete artifact");
                final var ended = injector.kill(nodes.first(),
                        committed ? CrashInjector.Point.AFTER_INTAKE_PUBLICATION_BEFORE_REPLY
                                : CrashInjector.Point.DURING_INTAKE_BEFORE_MANIFEST_COMPLETE);
                final ExecutionException unanswered = assertThrows(ExecutionException.class,
                        () -> lost.get(10, TimeUnit.SECONDS));
                assertInstanceOf(java.io.UncheckedIOException.class, unanswered.getCause());
                assertEquals(expected, post(nodes.second(), "intake-view", "all"));
                assertEquals(committed ? "ALREADY_COMPLETE" : "Written",
                        retryIntake());
                assertEquals("complete", post(nodes.second(), "intake-view", "all"));
                assertEquals("ALREADY_COMPLETE", retryIntake());
                return ended;
            } finally {
                if (injector.isRunning(nodes.first())) {
                    post(nodes.first(), "release", action);
                }
            }
        }
    }

    private String retryIntake() {
        // Oak can suspend a new commit until the killed cluster node's revisions are recovered.
        // This is one upload with a durability observation bound, never a transport retry loop.
        final var response = requests.submit(nodes.second().address() + ENDPOINT,
                List.of("action", "intake-retry", "fixture", "all"), java.time.Duration.ofMinutes(3));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private void assertReservation(String identifier) {
        final String path = "/var/slingshot-agent/capacity/reservations/"
                + identifier.substring(0, 2) + "/" + identifier.substring(2, 4) + "/" + identifier;
        final var response = requests.readAsAuthenticatedUser(nodes.second().address() + path + ".1.json");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"active\":true"), response.body());
    }

    private void race(String fixture, String action, String winner, List<String> losers)
            throws InterruptedException, ExecutionException, TimeoutException {
        assertEquals("armed", post(nodes.first(), "arm", fixture));
        assertEquals("armed", post(nodes.second(), "arm", fixture));
        try (var callers = Executors.newFixedThreadPool(2)) {
            try {
                racePrepared(callers, fixture, action, winner, losers);
            } finally {
                post(nodes.first(), "release", fixture);
                post(nodes.second(), "release", fixture);
            }
        }
    }

    private void racePrepared(java.util.concurrent.ExecutorService callers, String fixture,
                              String action, String winner, List<String> losers)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var first = callers.submit(() -> post(nodes.first(), action, fixture));
        awaitPrepared(nodes.first(), fixture, first);
        final var second = callers.submit(() -> post(nodes.second(), action, fixture));
        awaitPrepared(nodes.second(), fixture, second);
        assertEquals("released", post(nodes.first(), "release", fixture));
        assertEquals(winner, first.get(10, TimeUnit.SECONDS));
        assertEquals("released", post(nodes.second(), "release", fixture));
        final String loser = second.get(10, TimeUnit.SECONDS);
        assertTrue(losers.contains(loser), "the losing " + action + " returned " + loser);
    }

    private void awaitPrepared(ContainerHandle node, String fixture, Future<String> pending)
            throws InterruptedException, ExecutionException, TimeoutException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!"prepared".equals(requests.readAsAuthenticatedUser(node.address() + ENDPOINT
                + "?fixture=" + fixture).body())) {
            if (pending.isDone()) {
                throw new AssertionError("request ended before barrier: " + pending.get(1, TimeUnit.SECONDS));
            }
            assertTrue(System.nanoTime() < deadline, fixture + " did not reach its pre-commit barrier");
            Thread.sleep(100);
        }
    }

    private void awaitEffects(ContainerHandle node, String fixture) throws InterruptedException {
        await(() -> effects(node, fixture) == 1, "the effect was not independently observed");
        assertEffects(node, fixture);
    }

    private void assertEffects(ContainerHandle node, String fixture) {
        assertEquals(1, effects(node, fixture), "more than one content effect was committed");
    }

    private int effects(ContainerHandle node, String fixture) {
        final var response = requests.readAsAuthenticatedUser(node.address() + EFFECTS + fixture + ".1.json");
        assertEquals(200, response.statusCode(), response.body());
        // Observe distinct content nodes through Sling's own JSON servlet, independently of the
        // operation record and of what either contender claims about its execution.
        final int count = response.body().split("\"effect-", -1).length - 1;
        assertTrue(count <= 1, "duplicate effects: " + response.body());
        return count;
    }

    private String post(ContainerHandle node, String action, String fixture) {
        final HttpResponse<String> response = requests.submit(node.address() + ENDPOINT,
                List.of("action", action, "fixture", fixture));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private boolean observes(ContainerHandle node, String fixture, String expected) {
        final HttpResponse<String> response = requests.submit(node.address() + ENDPOINT,
                List.of("action", "view", "fixture", fixture));
        // Sling may rebuild servlet resolution after the shared fixture tree is created.
        // Only this read-only observation may wait through absence; mutations are never replayed.
        if (response.statusCode() == 404) {
            return false;
        }
        assertEquals(200, response.statusCode(), response.body());
        return expected.equals(response.body());
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, failure);
            Thread.sleep(100);
        }
    }

    private static Path bundle(Path repository) throws IOException {
        final Path target = repository.resolve("interop/target/exclusive-transition-probe.jar");
        final Path product = repository.resolve("core/target/slingshot-agent-core-0.2.0.jar");
        try (JarFile source = new JarFile(product.toFile())) {
            final Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
            manifest.getMainAttributes().putValue("Bundle-SymbolicName",
                    "rs.slingshot.agent.exclusive-transition-proof");
            manifest.getMainAttributes().putValue("Bundle-Version", "1.0.0");
            manifest.getMainAttributes().putValue("Bundle-Activator",
                    "rs.slingshot.agent.proof.ExclusiveTransitionProbe");
            manifest.getMainAttributes().putValue("Import-Package",
                    "org.osgi.framework.wiring,javax.jcr.version,"
                    + "javax.jcr.lock,javax.jcr.security,javax.jcr.retention,org.xml.sax,"
                    + source.getManifest().getMainAttributes()
                    .getValue("Import-Package"));
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                final var entries = source.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && !entry.getName().startsWith("META-INF/")
                            && !entry.getName().startsWith("OSGI-INF/")) {
                        output.putNextEntry(new JarEntry(entry.getName()));
                        try (var bytes = source.getInputStream(entry)) {
                            bytes.transferTo(output);
                        }
                        output.closeEntry();
                    }
                }
                addProofFiles(repository.resolve("core/target/test-classes"), output);
            }
        }
        return target;
    }

    private static void addProofFiles(Path root, JarOutputStream output) throws IOException {
        final List<String> names = List.of("rs/slingshot/agent/proof/ExclusiveTransitionProbe.class",
                "rs/slingshot/agent/proof/GenerationRotationProbe.class",
                "rs/slingshot/agent/proof/IntakePublicationProbe.class",
                "rs/slingshot/agent/proof/ExclusiveTransitionProbe$Barrier.class",
                "rs/slingshot/agent/store/SaveInterleaving.class",
                "rs/slingshot/agent/store/SaveInterleaving$Action.class",
                "fixtures/submission-admission/operation.json",
                "fixtures/submission-admission/command-contract.json");
        for (final String name : names) {
            output.putNextEntry(new JarEntry(name));
            Files.copy(root.resolve(name), output);
            output.closeEntry();
        }
    }
}
