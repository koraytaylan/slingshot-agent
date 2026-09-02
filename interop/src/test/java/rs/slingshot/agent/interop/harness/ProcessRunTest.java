// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one place this harness runs an external command.
 *
 * <p>Everything the harness asks the engine goes through here, so there is one answer to how a
 * command is built, how long it may take, and what happens to what it wrote. A harness that shelled
 * out in six places would have six answers and would eventually disagree with itself about one.</p>
 */
final class ProcessRunTest {

    /** More than any command here writes, and less than anything that would matter. */
    private static final long CAPTURE_BOUND = 4_194_304;

    @Test
    @DisplayName("a command that succeeds reports what it wrote")
    void aCommandThatSucceedsReportsWhatItWrote() {
        final ProcessRun run = ProcessRun.of(Duration.ofSeconds(10), CAPTURE_BOUND,
                List.of("printf", "answered"));
        assertTrue(run.succeeded(), run.output());
        assertEquals(0, run.status());
        assertEquals("answered", run.output());
        assertEquals(List.of("printf", "answered"), run.command());
    }

    @Test
    @DisplayName("a command that fails reports its status rather than throwing")
    void aCommandThatFailsReportsItsStatus() {
        final ProcessRun run = ProcessRun.of(Duration.ofSeconds(10), CAPTURE_BOUND,
                List.of("false"));
        assertFalse(run.succeeded());
        assertEquals(1, run.status());
    }

    @Test
    @DisplayName("a command that outlives its deadline is abandoned rather than waited on")
    void aCommandThatOutlivesItsDeadlineIsAbandoned() {
        final long before = System.nanoTime();
        final ProcessRun run = ProcessRun.of(Duration.ofMillis(200), CAPTURE_BOUND,
                List.of("sleep", "30"));
        final long elapsed = System.nanoTime() - before;
        assertEquals(ProcessRun.TIMED_OUT, run.status());
        assertTrue(elapsed < Duration.ofSeconds(30).toNanos(),
                "the command was waited on past its deadline");
    }

    @Test
    @DisplayName("a command that cannot be started at all is an engine that is not installed")
    void aCommandThatCannotBeStartedIsRaised() {
        assertThrows(UncheckedIOException.class, () -> ProcessRun.of(Duration.ofSeconds(10),
                        CAPTURE_BOUND, List.of("nothing-like-this-is-installed")),
                "a command nothing could start was reported as one that ran and failed");
    }

    @Test
    @DisplayName("a run interrupted while waiting stops waiting and says the thread was interrupted")
    void anInterruptedRunStopsWaiting() {
        Thread.currentThread().interrupt();
        final ProcessRun run = ProcessRun.of(Duration.ofSeconds(30), CAPTURE_BOUND,
                List.of("sleep", "30"));
        final boolean interrupted = Thread.interrupted();
        assertTrue(interrupted, "the interruption was swallowed rather than restored");
        assertEquals(ProcessRun.TIMED_OUT, run.status());
    }

    @Test
    @DisplayName("output is kept to the declared bound rather than to whatever was written")
    void outputIsKeptToTheDeclaredBound(@TempDir Path directory) throws IOException {
        final Path written = directory.resolve("captured.log");
        Files.writeString(written, "0123456789", StandardCharsets.UTF_8);
        assertEquals("01234", ProcessRun.read(written, 5));
        assertEquals("0123456789", ProcessRun.read(written, CAPTURE_BOUND));
    }
}
