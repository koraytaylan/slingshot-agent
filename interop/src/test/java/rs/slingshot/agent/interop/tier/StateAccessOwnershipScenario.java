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

/** Real state readers cannot bypass ownership, and bookkeeping cannot elevate caller content effects. */
final class StateAccessOwnershipScenario {

    private static final Path REPOSITORY = Path.of(System.getProperty("slingshot.repository.root"));
    private static final Path PRODUCT = REPOSITORY.resolve("core/target/slingshot-agent-core-0.1.0.jar");
    private static final String PROBE = "/bin/slingshot-proof/state-access";
    private static final String PREFIX = "/bin/slingshot/agent/";
    private static final String OPERATION =
            "d9bafa1904c7d41423a5f538531b7ae5577a255287d49e75ed6abde5df35c6b4";
    private static final String INTAKE = "9ae1cc6621203d8d4abe6cdb518732dc9f47142758682d2c3e05cf794505d1e6";
    private static final String QUERY = "?agent_operation_identifier=" + OPERATION;
    private final TierRequests requests = TierRequests.open();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void ownershipAndOriginalCallerPermissionsHoldAcrossEveryStateRoute()
            throws IOException, InterruptedException {
        SharedPublicSlingTier.release();
        final InteropTier tier = assertInstanceOf(InteropTier.Running.class,
                PublicSlingTier.start(REPOSITORY,
                        "localhost/slingshot-agent-public-sling:1", PRODUCT)).tier();
        try {
            install(tier);
            assertEquals("prepared", change(tier, List.of("action", "prepare")));
            await(() -> status(tier, "proof-owner", "submit", "POST", "{}") == 400);
            final String before = requests.readAsAuthenticatedUser(
                    tier.address() + "/content/state-access-proof.json").body();
            final String submission = fixture("submit-servlet/a-submission.json");
            answer(tier, "proof-owner", "submit", "POST", submission, 202);
            assertEquals("proof-owner:denied", requests.readAsAuthenticatedUser(
                    tier.address() + PROBE).body());
            assertEquals(before, requests.readAsAuthenticatedUser(
                    tier.address() + "/content/state-access-proof.json").body());
            // An operator may read another owner's operation but may not adopt it through a resend.
            answer(tier, "proof-operator", "submit", "POST", submission, 409);
            assertEquals("artifact", change(tier, List.of("action", "artifact", "operation", OPERATION)));
            answer(tier, "proof-removed", "snapshot" + QUERY, "GET", "", 200);
            assertEquals("removed", change(tier, List.of("action", "remove")));
            await(() -> status(tier, "proof-removed", "snapshot" + QUERY, "GET", "") == 404);
            for (final String user : List.of("proof-owner", "proof-operator", "proof-reader",
                    "proof-removed", "anonymous")) {
                readMatrix(tier, user);
            }
            intakeMatrix(tier);
            assertEquals(before, requests.readAsAuthenticatedUser(
                    tier.address() + "/content/state-access-proof.json").body());
        } finally {
            tier.stop();
        }
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY));
    }

    private void readMatrix(InteropTier tier, String user) throws IOException, InterruptedException {
        final int expected = List.of("proof-owner", "proof-operator").contains(user) ? 200
                : "anonymous".equals(user) ? 401 : 404;
        answer(tier, user, "snapshot" + QUERY, "GET", "", expected);
        answer(tier, user, "jobs" + QUERY, "GET", "", expected);
        answer(tier, user, "artifact" + QUERY + "&artifact_slot=proof-answer", "GET", "", expected);
        answer(tier, user, "subscriptions/high-water", "POST",
                "{\"daemon_subscription_identifier\":\"following-daemon-one\","
                        + "\"agent_event_store_generation\":1}", expected);
        stream(tier, user, expected);
        if ("proof-reader".equals(user) || "proof-removed".equals(user)) {
            final var visible = send(tier.address() + "/var/slingshot-agent/operations/g1/d9/ba/"
                    + OPERATION + ".json", user, "GET", "", "application/json");
            assertEquals(200, visible.statusCode(), "the denied caller must really see the state record");
            assertTrue(visible.body().contains("proof-owner"), visible.body());
        }
    }

    private void intakeMatrix(InteropTier tier) throws IOException, InterruptedException {
        final String body = fixture("artifact-intake/declaring-a-payload.json")
                .replace("following-daemon-one", "intake-following");
        answer(tier, "proof-owner", "submit", "POST", body, 202);
        final String path = "artifact?agent_operation_identifier=" + INTAKE + "&artifact_slot=payload";
        final String payload = Files.readString(REPOSITORY.resolve(
                "core/src/test/resources/fixtures/artifact-intake/payload.txt"));
        for (final String user : List.of("proof-reader", "proof-removed", "anonymous")) {
            answer(tier, user, path, "POST", payload, "anonymous".equals(user) ? 401 : 404);
        }
        answer(tier, "proof-owner", path, "POST", payload, 204);
        final String second = "f".repeat(64);
        answer(tier, "proof-owner", "submit", "POST", body.replace(INTAKE, second)
                .replace("intake-following", "intake-operator"), 202);
        answer(tier, "proof-operator", path.replace(INTAKE, second), "POST", payload, 204);
    }

    private void stream(InteropTier tier, String user, int expected)
            throws IOException, InterruptedException {
        final String address = tier.address() + PREFIX + "events" + QUERY
                + "&daemon_subscription_identifier=following-daemon-one&agent_event_store_generation=1";
        final var response = client.send(request(address, user, "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            assertEquals(expected, response.statusCode(), user + " event stream");
            if (expected == 200) {
                assertTrue(body.read() >= 0, "the admitted stream produced no bytes");
            }
        }
    }

    private void install(InteropTier tier) throws IOException, InterruptedException {
        final var installed = requests.upload(tier.address() + "/system/console/bundles",
                List.of("action", "install", "bundlestart", "start"), "bundlefile", probe());
        assertTrue(installed.statusCode() < 400, installed.body());
        await(() -> "not-run".equals(requests.readAsAuthenticatedUser(tier.address() + PROBE).body()));
    }

    private String change(InteropTier tier, List<String> parameters) {
        final var response = requests.submit(tier.address() + PROBE, parameters);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private int status(InteropTier tier, String user, String path, String method, String body) {
        try {
            return send(tier.address() + PREFIX + path, user, method, body, "application/json").statusCode();
        } catch (final IOException failed) {
            throw new IllegalStateException(failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void answer(InteropTier tier, String user, String path, String method, String body, int expected)
            throws IOException, InterruptedException {
        final String media = path.startsWith("artifact?") ? "application/octet-stream" : "application/json";
        final var response = send(tier.address() + PREFIX + path, user, method, body, media);
        assertEquals(expected, response.statusCode(),
                user + " " + method + " " + path + ": " + response.body());
        if (expected >= 400) {
            assertEquals("", response.body(), "a refused state request must disclose no body");
        }
    }

    private HttpResponse<String> send(String address, String user, String method, String body, String media)
            throws IOException, InterruptedException {
        return client.send(request(address, user, media).method(method,
                "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String address, String user, String media) {
        final var request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(20))
                .header("Referer", address).header("Content-Type", media);
        if (!"anonymous".equals(user)) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":proof-password").getBytes(StandardCharsets.UTF_8)));
        }
        return request;
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(REPOSITORY.resolve("core/src/test/resources/fixtures/" + name))
                .replaceAll("\"request_start_unix_milliseconds\": [0-9]+",
                        "\"request_start_unix_milliseconds\": " + System.currentTimeMillis());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "the installed state proof did not become ready");
            Thread.sleep(100);
        }
    }

    private static Path probe() throws IOException {
        final Path target = REPOSITORY.resolve("interop/target/state-access-probe.jar");
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", "rs.slingshot.agent.state-access-proof");
        manifest.getMainAttributes().putValue("Bundle-Version", "1.0.0");
        manifest.getMainAttributes().putValue("Bundle-Activator",
                "rs.slingshot.agent.proof.StateAccessProbe");
        try (JarFile product = new JarFile(PRODUCT.toFile())) {
            manifest.getMainAttributes().putValue("Import-Package",
                    "org.osgi.framework.wiring,org.osgi.service.cm,"
                    + "javax.jcr.security,org.apache.jackrabbit.api.security,"
                    + product.getManifest().getMainAttributes().getValue("Import-Package"));
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            for (final String suffix : List.of("StateAccessProbe", "StateAccessProbe$FixtureLoader",
                    "StateAccessFixtures", "StateAccessFixtures$ContentAttempt")) {
                final String name = "rs/slingshot/agent/proof/" + suffix + ".class";
                output.putNextEntry(new JarEntry(name));
                Files.copy(REPOSITORY.resolve("core/target/test-classes").resolve(name), output);
                output.closeEntry();
            }
        }
        return target;
    }
}
