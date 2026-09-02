// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One run of one external command, and what it did.
 *
 * <p>Everything this harness asks the container engine goes through here, so there is one place
 * that decides how a command is built, how long it may take, and what happens to what it wrote.
 * A suite that shelled out in six places would have six answers to those questions and would
 * eventually disagree with itself about one of them.</p>
 *
 * @param command what was run, argument by argument
 * @param status what it exited with
 * @param output what it wrote, bounded
 */
public record ProcessRun(List<String> command, int status, String output) {

    /** What a command that was still running when its deadline passed is recorded as. */
    public static final int TIMED_OUT = -1;

    /**
     * Holds a run whose command nothing can change afterwards.
     */
    public ProcessRun {
        command = List.copyOf(command);
    }

    /**
     * What was run.
     *
     * @return the command, as a view nothing can change
     */
    @Override
    public List<String> command() {
        return List.copyOf(command);
    }

    /**
     * Whether the command did what it was asked.
     *
     * @return whether it exited reporting success
     */
    public boolean succeeded() {
        return status == 0;
    }

    /**
     * Runs a command and waits for it, bounded.
     *
     * @param deadline how long it may take before it is abandoned
     * @param maximumCapturedBytes the most of its output to keep
     * @param command what to run
     * @return what it did
     * @throws UncheckedIOException if the command cannot be started at all, which is an engine that
     *     is not installed rather than one that refused
     */
    public static ProcessRun of(Duration deadline, long maximumCapturedBytes, List<String> command) {
        return of(deadline, maximumCapturedBytes, command, java.util.Map.of());
    }

    /**
     * Runs a command with an environment of somebody's choosing, and waits for it, bounded.
     *
     * <p>The environment is what a process reads before it reads anything else, and a tier that
     * ran an executable with this one's environment would be proving what this machine is set up
     * as rather than what the executable does.</p>
     *
     * @param deadline how long it may take before it is abandoned
     * @param maximumCapturedBytes the most of its output to keep
     * @param command what to run
     * @param environment what to add to its environment, which nothing else changes
     * @return what it did
     * @throws UncheckedIOException if the command cannot be started at all
     */
    public static ProcessRun of(Duration deadline, long maximumCapturedBytes, List<String> command,
                                java.util.Map<String, String> environment) {
        try {
            final Path captured = Files.createTempFile("slingshot-agent-interop", ".log");
            final ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(captured.toFile());
            builder.environment().putAll(environment);
            final Process process = builder.start();
            final boolean finished = process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessRun(command, TIMED_OUT, read(captured, maximumCapturedBytes));
            }
            return new ProcessRun(command, process.exitValue(), read(captured, maximumCapturedBytes));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ProcessRun(command, TIMED_OUT, "");
        }
    }

    /**
     * Reads what a command wrote, keeping at most the declared bound.
     *
     * @param captured where the output was written
     * @param maximumCapturedBytes the most to keep
     * @return the output, truncated at the bound
     */
    public static String read(Path captured, long maximumCapturedBytes) {
        try {
            final byte[] bytes = Files.readAllBytes(captured);
            final int kept = (int) Math.min(bytes.length, maximumCapturedBytes);
            return new String(bytes, 0, kept, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
