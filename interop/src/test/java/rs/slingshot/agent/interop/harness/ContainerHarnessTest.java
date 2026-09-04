// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.slingshot.agent.interop.tier.SharedPublicSlingTier;

/**
 * The harness every interoperability tier depends on behaving.
 *
 * <p>Two things are proved that a suite about containers usually gets wrong. That a container which
 * exits during startup is a different failure from one that never becomes ready — the causes differ
 * and reporting them as one leaves the reader to work out which happened. And that cleanup goes
 * through the handle that started the container rather than through its name, which is what stops a
 * replacement that reused a name from being stopped by a suite that never started it.</p>
 */
final class ContainerHarnessTest {

    private static final Path REPOSITORY = repositoryRoot();

    /** The image the harness proves itself against, which starts in about no time at all. */
    private static final String PROBE_IMAGE = "docker.io/library/alpine:3";

    /** A port nothing in the probe image listens on, which is what makes readiness decidable here. */
    private static final int PROBE_PORT = 8080;

    private final ContainerHarness harness = ContainerHarness.at(REPOSITORY);

    @AfterEach
    void nothingIsLeftBehind() {
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "this suite left a container running that it started");
    }

    @Test
    @DisplayName("the harness reads its own bounds rather than carrying them")
    void theHarnessReadsItsOwnBounds() {
        assertEquals("podman", harness.engine());
        assertEquals(Duration.ofMillis(300_000), harness.readinessDeadline());
        final String values = read(REPOSITORY.resolve("support/interop-harness.toml"));
        assertTrue(values.contains("readiness_deadline_milliseconds = 300000"),
                "the deadline is not the one the values declare");
    }

    @Test
    @DisplayName("an image this engine does not hold is refused and nothing is pulled")
    void anAbsentImageIsRefusedRatherThanPulled() {
        final ContainerHarness.Outcome outcome = harness.start(
                "docker.invalid/nothing/like-this:0", PROBE_PORT, List.of(), handle -> true);
        final ContainerHarness.Refused refused = assertInstanceOf(ContainerHarness.Refused.class,
                outcome, "an image nothing holds produced a container");
        assertEquals(ContainerHarness.Failure.IMAGE_ABSENT, refused.failure());
        assertTrue(refused.detail().contains("scripts/prepare_interop_images"),
                "the refusal does not name the preparation command: " + refused.detail());
    }

    @Test
    @DisplayName("a container that exits during startup is a different failure from one that hangs")
    void exitingAndHangingAreDifferentFailures() {
        assertTrue(harness.holds(PROBE_IMAGE),
                PROBE_IMAGE + " is not held; run scripts/prepare_interop_images");
        final ContainerHarness.Outcome exiting =
                harness.start(PROBE_IMAGE, PROBE_PORT, List.of(), handle -> false);
        final ContainerHarness.Refused refused = assertInstanceOf(ContainerHarness.Refused.class,
                exiting, "a container that exits immediately was reported ready");
        assertEquals(ContainerHarness.Failure.EXITED_DURING_STARTUP, refused.failure());
        assertTrue(refused.detail().contains("exited before it was ready"), refused.detail());
        assertNotEquals(ContainerHarness.Failure.NEVER_BECAME_READY, refused.failure(),
                "a container that exited was reported as one that never became ready");
    }

    @Test
    @DisplayName("a container that stays up and never becomes ready is its own failure")
    void stayingUpAndNeverBecomingReadyIsItsOwnFailure() {
        final ContainerHarness shortDeadline = ContainerHarness.from(REPOSITORY.resolve(
                "interop/src/test/resources/fixtures/container-harness/short-deadline.toml"));
        final long before = System.nanoTime();
        final ContainerHarness.Outcome outcome = shortDeadline.start(PROBE_IMAGE, PROBE_PORT,
                List.of(), List.of("sleep", "600"), handle -> false);
        final long elapsed = System.nanoTime() - before;
        final ContainerHarness.Refused refused = assertInstanceOf(ContainerHarness.Refused.class,
                outcome, "a container nothing made ready was reported ready");
        assertEquals(ContainerHarness.Failure.NEVER_BECAME_READY, refused.failure());
        assertTrue(elapsed >= shortDeadline.readinessDeadline().toNanos(),
                "the deadline was not the one the values declare");
    }

    @Test
    @DisplayName("a container that becomes ready is returned with its handle and its output")
    void aReadyContainerIsReturnedWithItsHandle() {
        final ContainerHarness.Outcome outcome = harness.start(PROBE_IMAGE, PROBE_PORT,
                List.of(), List.of("sleep", "600"), handle -> true);
        final ContainerHandle handle = assertInstanceOf(ContainerHarness.Started.class, outcome,
                "a container that was ready was refused: " + outcome).handle();
        assertTrue(handle.mappedPort() > 0, "the container published no port on this machine");
        assertTrue(handle.address().startsWith("http://localhost:"), handle.address());
        assertTrue(Files.isRegularFile(handle.capturedOutput()),
                "the container's output is held rather than written to a file");
        harness.stop(handle);
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "stopping through the retained handle left the container running");
    }

    @Test
    @DisplayName("a handle names the exact container it started and nothing else")
    void aHandleNamesTheContainerItStarted(@TempDir Path directory) {
        final ContainerHandle handle = new ContainerHandle("abc123", PROBE_IMAGE,
                directory.resolve("probe.log"), 42_000);
        assertEquals("abc123", handle.identifier());
        assertEquals("http://localhost:42000", handle.address());
        assertThrowsOnBlankIdentifier(directory);
    }

    @Test
    @DisplayName("captured output goes to a file rather than being held")
    void capturedOutputGoesToAFile(@TempDir Path directory) {
        final ContainerHandle handle = new ContainerHandle("abc123", PROBE_IMAGE,
                directory.resolve("probe.log"), 42_000);
        assertTrue(handle.capturedOutput().toString().endsWith(".log"),
                "the capture is not a file at all");
    }

    private static void assertThrowsOnBlankIdentifier(Path directory) {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ContainerHandle("", PROBE_IMAGE, directory.resolve("nothing.log"), 1),
                "a handle naming no container was accepted");
        assertTrue(refused.getMessage().contains("names no container"), refused.getMessage());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
