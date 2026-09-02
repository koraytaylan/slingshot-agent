// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Ending an instance the way a machine ends one, at a point somebody chose.
 *
 * <p>Every durability claim in this repository is about what survives a process ending between two
 * steps. A unit test can prove the shape of a commit; only killing the instance proves the shape
 * was the one that mattered — and killing it has to mean killing it. A graceful stop gives the
 * runtime a chance to flush, close, and tidy, which is exactly the chance a power cut does not
 * give, so what a graceful stop proves is that the tidying works.</p>
 *
 * <p>So this sends the signal a process cannot handle, waits for the container to actually be gone
 * from the engine's point of view, and then starts the same container again — the same one, because
 * a replacement would have a new filesystem and the whole question is what was on the old one.</p>
 */
public final class CrashInjector {

    /** The signal a process cannot catch, handle, or ignore. */
    private static final String UNCATCHABLE = "KILL";

    /** What the shell reports for a process that was killed by that signal. */
    public static final int KILLED = 137;

    private final String engine;

    private final Duration bound;

    private final Duration pollInterval;

    private final long maximumCapturedBytes;

    private CrashInjector(String engine, Duration bound, Duration pollInterval,
                          long maximumCapturedBytes) {
        this.engine = engine;
        this.bound = bound;
        this.pollInterval = pollInterval;
        this.maximumCapturedBytes = maximumCapturedBytes;
    }

    /**
     * An injector driving the same engine the harness drives.
     *
     * @param harness the harness whose engine and timing this uses
     * @return the injector
     */
    public static CrashInjector alongside(ContainerHarness harness) {
        return new CrashInjector(harness.engine(), harness.readinessDeadline(),
                Duration.ofMillis(POLL_INTERVAL_MILLISECONDS), harness.maximumCapturedBytes());
    }

    /** How often the engine is asked whether a container has finished dying. */
    private static final long POLL_INTERVAL_MILLISECONDS = 250;

    /**
     * Where a process may be ended, enumerated before anything was written to end it.
     *
     * <p>Each point is a gap between two writes that a client's own recovery path has to be able to
     * cross. They are named rather than numbered because a scenario reporting "crash point three"
     * tells nobody what broke.</p>
     */
    public enum Point {

        /** Between the submission being admitted and the record moving to running. */
        AFTER_ADMISSION_BEFORE_START("after_admission_before_start"),

        /** Between the record moving to running and the command's own commit. */
        AFTER_START_BEFORE_COMMAND_COMMIT("after_start_before_command_commit"),

        /** Between the command's own commit and the commit that ends the operation. */
        AFTER_COMMAND_COMMIT_BEFORE_TERMINAL("after_command_commit_before_terminal"),

        /** While declared payloads are still arriving. */
        DURING_INTAKE_BEFORE_MANIFEST_COMPLETE("during_intake_before_manifest_complete"),

        /** Between an artifact's bytes being committed and anything naming them. */
        AFTER_ARTIFACT_BYTES_BEFORE_REFERENCE("after_artifact_bytes_before_reference"),

        /** Between the commit that ends the operation and the client hearing about it. */
        AFTER_TERMINAL_BEFORE_ACKNOWLEDGEMENT("after_terminal_before_acknowledgement"),

        /** Part-way through a maintenance pass. */
        DURING_A_SWEEP("during_a_sweep");

        private final String spelling;

        Point(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How this point is spelled where it is written down.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The point one spelling names.
         *
         * @param spelling the spelling
         * @return the point, or nothing where no such point is enumerated
         */
        public static Optional<Point> named(String spelling) {
            return Arrays.stream(values())
                    .filter(point -> point.spelling.equals(spelling))
                    .findFirst();
        }
    }

    /**
     * What happened to a container that was ended.
     *
     * @param point where it was ended
     * @param exitStatus what the engine reports it exited with
     * @param graceful whether anything was given a chance to tidy up, which is never
     */
    public record Ended(Point point, int exitStatus, Graceful graceful) {
    }

    /** Whether an ending gave the process a chance to tidy up. */
    public enum Graceful {
        /** It did not, which is the only kind this injector performs. */
        NOTHING_WAS_FLUSHED
    }

    /**
     * Ends a running container at a named point, without a graceful shutdown.
     *
     * @param handle the container to end
     * @param point where the scenario is ending it
     * @return what the engine reports about the ending
     */
    public Ended kill(ContainerHandle handle, Point point) {
        run(List.of(engine, "kill", "--signal", UNCATCHABLE, handle.identifier()));
        awaitStopped(handle.identifier());
        return new Ended(point, exitStatus(handle.identifier()), Graceful.NOTHING_WAS_FLUSHED);
    }

    /**
     * Starts the same container again, against the same durable state it left behind.
     *
     * @param handle the container that was ended
     * @return what the engine did
     */
    public ProcessRun restart(ContainerHandle handle) {
        final ProcessRun started = run(List.of(engine, "start", handle.identifier()));
        awaitRunning(handle.identifier());
        return started;
    }

    /**
     * Starts the same container again and says where it now answers.
     *
     * <p>The address is read again rather than assumed: the engine republishes a port when a
     * container starts, and a scenario that kept the old one would be asking a port nothing is
     * listening on and calling the answer a durability failure.</p>
     *
     * @param handle the container that was ended
     * @param containerPort the port it answers on inside itself
     * @return the container as it now is, or nothing where the engine will not say where it is
     */
    public Optional<ContainerHandle> restarted(ContainerHandle handle, int containerPort) {
        restart(handle);
        return mappedPort(handle.identifier(), containerPort)
                .map(port -> new ContainerHandle(handle.identifier(), handle.image(),
                        handle.capturedOutput(), port));
    }

    private Optional<Integer> mappedPort(String identifier, int containerPort) {
        final ProcessRun published = run(List.of(engine, "port", identifier,
                containerPort + "/tcp"));
        final String line = published.output().strip();
        final int colon = line.lastIndexOf(':');
        if (!published.succeeded() || colon < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(line.substring(colon + 1).strip()));
        } catch (final NumberFormatException notAPort) {
            return Optional.empty();
        }
    }

    /**
     * Whether the engine currently holds this container as running.
     *
     * @param handle the container
     * @return what was observed
     */
    public boolean isRunning(ContainerHandle handle) {
        return "true".equals(run(List.of(engine, "inspect", "--format", "{{.State.Running}}",
                handle.identifier())).output().strip());
    }

    /**
     * What the engine reports a container exited with.
     *
     * @param identifier the container
     * @return the exit status, or a status nothing exits with where the engine will not say
     */
    private int exitStatus(String identifier) {
        final ProcessRun inspected = run(List.of(engine, "inspect", "--format",
                "{{.State.ExitCode}}", identifier));
        try {
            return Integer.parseInt(inspected.output().strip());
        } catch (final NumberFormatException unreadable) {
            // An engine that will not say is not the same as a container that exited cleanly, so
            // this is a value no process produces rather than zero.
            return -1;
        }
    }

    private void awaitStopped(String identifier) {
        final long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline && "true".equals(run(List.of(engine, "inspect",
                "--format", "{{.State.Running}}", identifier)).output().strip())) {
            pause();
        }
    }

    private void awaitRunning(String identifier) {
        final long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline && !"true".equals(run(List.of(engine, "inspect",
                "--format", "{{.State.Running}}", identifier)).output().strip())) {
            pause();
        }
    }

    private ProcessRun run(List<String> command) {
        return ProcessRun.of(bound, maximumCapturedBytes, command);
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
