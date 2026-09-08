// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

/** Live Config Admin updates govern real caller sessions in the installed, unrestarted bundle. */
final class LiveOperatorConfigurationScenario {

    private static final Path REPOSITORY = Path.of(System.getProperty("slingshot.repository.root"));
    private static final Path PRODUCT = REPOSITORY.resolve("core/target/slingshot-agent-core-0.2.0.jar");
    private static final String ENDPOINT = "/bin/slingshot-proof/authorization";
    private final TierRequests requests = TierRequests.open();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void updatesGrantAndRevokeSubmissionAndDiagnosticsWithoutRestart()
            throws IOException, InterruptedException {
        SharedPublicSlingTier.release();
        final InteropTier tier = assertInstanceOf(InteropTier.Running.class,
                PublicSlingTier.start(REPOSITORY,
                        "localhost/slingshot-agent-public-sling:1", PRODUCT)).tier();
        try {
            install(tier);
            assertEquals("done", change(tier, "prepare"));
            await(() -> "[administrators]/0".equals(state(tier)));
            access(tier, "admin", true);
            access(tier, "proof-operator-one", false);
            access(tier, "proof-operator-two", false);
            configure(tier, "proof-operators-one", "[proof-operators-one]/0");
            access(tier, "proof-operator-one", true);
            access(tier, "proof-operator-two", false);
            access(tier, "admin", false);
            configure(tier, "proof-operators-two", "[proof-operators-two]/0");
            access(tier, "proof-operator-one", false);
            access(tier, "proof-operator-two", true);
            access(tier, "admin", false);
            configure(tier, "missing-proof-group", "[missing-proof-group]/0");
            access(tier, "proof-operator-two", false);
            configure(tier, "empty", "[]/0");
            access(tier, "admin", false);
            access(tier, "proof-operator-one", false);
            access(tier, "proof-operator-two", false);
            configure(tier, "delete", "[administrators]/0");
            access(tier, "admin", true);
            access(tier, "proof-operator-two", false);
        } finally {
            tier.stop();
        }
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY));
    }

    private void install(InteropTier tier) throws IOException, InterruptedException {
        final var installed = requests.upload(tier.address() + "/system/console/bundles",
                List.of("action", "install", "bundlestart", "start"), "bundlefile", probe());
        assertTrue(installed.statusCode() < 400, installed.body());
        await(() -> "[administrators]/0".equals(state(tier)));
    }

    private void configure(InteropTier tier, String group, String expected) throws InterruptedException {
        assertEquals("done", change(tier, group));
        await(() -> expected.equals(state(tier)));
    }

    private String change(InteropTier tier, String action) {
        final var answer = requests.submit(tier.address() + ENDPOINT, List.of("action", action));
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    private String state(InteropTier tier) {
        return requests.readAsAuthenticatedUser(tier.address() + ENDPOINT + "?action=state").body();
    }

    private void access(InteropTier tier, String user, boolean admitted) throws IOException,
            InterruptedException {
        final String credential = user + ":" + ("admin".equals(user) ? "admin" : "proof-password");
        final String authorization = "Basic " + Base64.getEncoder().encodeToString(
                credential.getBytes(StandardCharsets.UTF_8));
        final String submit = tier.address() + "/bin/slingshot/agent/submit";
        final var submitted = client.send(HttpRequest.newBuilder(URI.create(submit))
                .timeout(Duration.ofSeconds(10)).header("Authorization", authorization)
                .header("Content-Type", "application/json").header("Referer", submit)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(admitted ? 400 : 403, submitted.statusCode(), user + ": " + submitted.body());
        // A 400 proves passage through authorization to malformed-body validation, not command execution.
        final var console = client.send(HttpRequest.newBuilder(URI.create(tier.address() + ENDPOINT))
                .timeout(Duration.ofSeconds(10)).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, console.statusCode(), console.body());
        assertEquals(admitted ? "Unreadable" : "Denied", console.body(), user);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "the live authorization state did not converge");
            Thread.sleep(100);
        }
    }

    private static Path probe() throws IOException {
        final Path target = REPOSITORY.resolve("interop/target/operator-authorization-probe.jar");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", "rs.slingshot.agent.operator-proof");
        manifest.getMainAttributes().putValue("Bundle-Version", "1.0.0");
        manifest.getMainAttributes().putValue("Bundle-Activator",
                "rs.slingshot.agent.proof.OperatorAuthorizationProbe");
        try (JarFile product = new JarFile(PRODUCT.toFile())) {
            manifest.getMainAttributes().putValue("Import-Package",
                    "org.osgi.framework.wiring,org.osgi.service.cm,"
                    + product.getManifest().getMainAttributes().getValue("Import-Package"));
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            final String name = "rs/slingshot/agent/proof/OperatorAuthorizationProbe.class";
            output.putNextEntry(new JarEntry(name));
            Files.copy(REPOSITORY.resolve("core/target/test-classes").resolve(name), output);
            output.closeEntry();
        }
        return target;
    }
}
