// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The one wrapper every interoperability tier starts a container through.
 *
 * <p>It drives the engine rootlessly, declares the ports it needs and no others, captures output to
 * a bounded file rather than to memory, and hands back a handle that retains the exact container it
 * started. Nothing here reaches a daemon socket and nothing takes a container-orchestration test
 * dependency: the harness is the thing every suite depends on behaving, so it is code somebody here
 * can read.</p>
 *
 * <p>Readiness is a condition the caller states and this polls, under one absolute deadline. A
 * container that exits during startup and one that never becomes ready are two different failures
 * because they have two different causes, and reporting them as one would leave whoever reads the
 * failure to work out which happened.</p>
 */
public final class ContainerHarness {

    private static final String VALUES_FILE = "support/interop-harness.toml";

    /** How this harness marks what it started, so a leak check finds its own work and no other. */
    public static final String HARNESS_LABEL = "rs.slingshot.agent.interop";

    private final String engine;
    private final Duration readinessDeadline;

    private final Duration publishedRuntimeReadinessDeadline;
    private final Duration pollInterval;
    private final Duration stopGrace;
    private final long maximumCapturedBytes;

    private ContainerHarness(String engine, Duration readinessDeadline,
                             Duration publishedRuntimeReadinessDeadline, Duration pollInterval,
                             Duration stopGrace, long maximumCapturedBytes) {
        this.engine = engine;
        this.readinessDeadline = readinessDeadline;
        this.publishedRuntimeReadinessDeadline = publishedRuntimeReadinessDeadline;
        this.pollInterval = pollInterval;
        this.stopGrace = stopGrace;
        this.maximumCapturedBytes = maximumCapturedBytes;
    }

    /** Why a container is not there to be used. */
    public enum Failure {
        /** The pinned image is not present, and this harness pulls nothing. */
        IMAGE_ABSENT,
        /** The engine refused to start it at all. */
        REFUSED_BY_THE_ENGINE,
        /** It started and then exited before it was ready, which is a different cause. */
        EXITED_DURING_STARTUP,
        /** It stayed running and never satisfied the readiness condition inside the deadline. */
        NEVER_BECAME_READY
    }

    /** The result of starting one: a container, or the one reason there is none. */
    public sealed interface Outcome permits Started, Refused {
    }

    /**
     * A container that started and became ready.
     *
     * @param handle the handle that retains it
     */
    public record Started(ContainerHandle handle) implements Outcome {
    }

    /**
     * A start that produced no usable container.
     *
     * @param failure why there is none
     * @param detail what was observed, so the cause is readable rather than inferred
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Reads the harness's own values.
     *
     * @param root the repository root
     * @return the harness
     * @throws IllegalStateException if the values do not parse, because a harness with no bounds is
     *     one whose failures would be about the machine
     */
    public static ContainerHarness at(Path root) {
        return from(root.resolve(VALUES_FILE));
    }

    /**
     * Reads harness values from wherever they sit.
     *
     * <p>A suite proving what the deadline does reads a fixture that declares a short one; a suite
     * proving what the deadline is reads the committed file. Doing both from the committed file
     * would mean every run of the first waited out the second.</p>
     *
     * @param values the values document
     * @return the harness
     */
    public static ContainerHarness from(Path values) {
        return read(parse(values));
    }

    private static ContainerHarness read(TomlParseResult values) {
        return new ContainerHarness(
                required(values, "engine.executable"),
                milliseconds(values, "timing.readiness_deadline_milliseconds"),
                millisecondsOr(values, "timing.published_runtime_readiness_deadline_milliseconds",
                        milliseconds(values, "timing.readiness_deadline_milliseconds")),
                milliseconds(values, "timing.readiness_poll_interval_milliseconds"),
                milliseconds(values, "timing.stop_grace_milliseconds"),
                requiredNumber(values, "capture.maximum_captured_bytes"));
    }

    /**
     * The same harness, holding a start to what a published runtime takes rather than to what the
     * slowest tier does.
     *
     * <p>The deadline in the values covers an Adobe quickstart, because it has to cover the slowest
     * thing any tier starts. A tier that starts a published Sling runtime is not that, and holding
     * it to that deadline means every one of its failures costs five minutes to discover.</p>
     *
     * @return a harness whose readiness deadline is the published runtime's
     */
    public ContainerHarness forPublishedRuntime() {
        return new ContainerHarness(engine, publishedRuntimeReadinessDeadline,
                publishedRuntimeReadinessDeadline, pollInterval, stopGrace, maximumCapturedBytes);
    }

    /**
     * How long a start may take before it is a failure.
     *
     * @return the readiness deadline the values declare
     */
    public Duration readinessDeadline() {
        return readinessDeadline;
    }

    /**
     * The engine this harness drives.
     *
     * @return the engine's executable name
     */
    public String engine() {
        return engine;
    }

    /**
     * Whether the engine already holds an image, which this harness never pulls.
     *
     * @param image the image, at the digest it is pinned to
     * @return whether it is present
     */
    public boolean holds(String image) {
        return run(List.of(engine, "image", "exists", image)).succeeded();
    }

    /**
     * Starts a container and waits for the caller's own readiness condition.
     *
     * @param image the pinned image to start
     * @param containerPort the one port inside the container this tier reaches
     * @param environment values to set inside it, as {@code NAME=value}
     * @param ready what makes it ready, asked of the address it answers on
     * @return the container, or the one reason there is none
     */
    public Outcome start(String image, int containerPort, List<String> environment,
                         Predicate<ContainerHandle> ready) {
        return start(image, containerPort, environment, List.of(), ready);
    }

    /**
     * Starts a container running a command of the caller's own, and waits for their readiness
     * condition.
     *
     * @param image the pinned image to start
     * @param containerPort the one port inside the container this tier reaches
     * @param environment values to set inside it, as {@code NAME=value}
     * @param containerCommand what to run inside it, empty for the image's own entry point
     * @param ready what makes it ready, asked of the address it answers on
     * @return the container, or the one reason there is none
     */
    public Outcome start(String image, int containerPort, List<String> environment,
                         List<String> containerCommand, Predicate<ContainerHandle> ready) {
        return start(image, containerPort, environment, containerCommand, Attachment.none(), ready);
    }

    /**
     * What a container is attached to, where more than one of them has to reach another.
     *
     * <p>Containers reach one another by name on a network of their own rather than through a port
     * published on the host: a store published on the host would be a store anything on the machine
     * could reach, and the harness's claim to expose nothing but the ports a tier declares would
     * stop being true.</p>
     *
     * @param network the network to attach to, or empty for none
     * @param alias the name other containers on it reach this one by, or empty for none
     */
    public record Attachment(String network, String alias) {

        /**
         * No network at all, which is what a single instance needs.
         *
         * @return the attachment
         */
        public static Attachment none() {
            return new Attachment("", "");
        }

        /**
         * Whether this attaches to anything.
         *
         * @return whether there is a network
         */
        public boolean isAttached() {
            return !network.isEmpty();
        }
    }

    /**
     * Starts a container attached to a network other containers reach it on.
     *
     * @param image the pinned image to start
     * @param containerPort the one port inside the container this tier reaches
     * @param environment values to set inside it, as {@code NAME=value}
     * @param containerCommand what to run inside it, empty for the image's own entry point
     * @param attachment what it is attached to, and what other containers reach it by
     * @param ready what makes it ready, asked of the address it answers on
     * @return the container, or the one reason there is none
     */
    public Outcome start(String image, int containerPort, List<String> environment,
                         List<String> containerCommand, Attachment attachment,
                         Predicate<ContainerHandle> ready) {
        if (!holds(image)) {
            return new Refused(Failure.IMAGE_ABSENT,
                    image + " is not held by this engine; run scripts/prepare_interop_images");
        }
        final String name = "slingshot-agent-interop-" + UUID.randomUUID();
        final List<String> command = new ArrayList<>(List.of(engine, "run", "--detach",
                "--name", name, "--label", "harness=" + HARNESS_LABEL,
                "--publish", "127.0.0.1::" + containerPort));
        if (attachment.isAttached()) {
            command.add("--network");
            command.add(attachment.network());
            command.add("--network-alias");
            command.add(attachment.alias());
        }
        environment.forEach(value -> {
            command.add("--env");
            command.add(value);
        });
        command.add(image);
        command.addAll(containerCommand);
        final ProcessRun started = run(command);
        if (!started.succeeded()) {
            return new Refused(Failure.REFUSED_BY_THE_ENGINE, started.output());
        }
        // The engine writes the identifier last: anything before it is a warning it decided to
        // mention, and taking the whole output would give a name that stops and removes nothing.
        final String identifier = started.output().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .reduce("", (all, line) -> line);
        final Optional<Integer> port = mappedPort(identifier, containerPort);
        if (port.isEmpty()) {
            // A container that has already exited publishes nothing, and reporting that as the
            // engine refusing would hide the cause behind its symptom.
            final Failure failure = isRunning(identifier)
                    ? Failure.REFUSED_BY_THE_ENGINE
                    : Failure.EXITED_DURING_STARTUP;
            final String observed = failure == Failure.EXITED_DURING_STARTUP
                    ? identifier + " exited before it was ready"
                    : identifier + " published no port for " + containerPort;
            stop(identifier);
            return new Refused(failure, observed);
        }
        final ContainerHandle handle =
                new ContainerHandle(identifier, image, captureFile(identifier), port.get());
        return awaitReadiness(handle, ready);
    }

    private Outcome awaitReadiness(ContainerHandle handle, Predicate<ContainerHandle> ready) {
        final long deadline = System.nanoTime() + readinessDeadline.toNanos();
        while (System.nanoTime() < deadline) {
            if (!isRunning(handle)) {
                capture(handle);
                stop(handle.identifier());
                return new Refused(Failure.EXITED_DURING_STARTUP,
                        handle.identifier() + " exited before it was ready");
            }
            if (ready.test(handle)) {
                capture(handle);
                return new Started(handle);
            }
            pause();
        }
        capture(handle);
        stop(handle.identifier());
        // What it wrote while it failed to become ready is the only evidence of why, and a refusal
        // that dropped it would leave whoever reads the failure with the fact and no cause. The
        // path alone is not enough: on a runner the file goes when the job does, so the end of it
        // travels in the refusal itself - otherwise this is only diagnosable by running it again
        // somewhere the file survives, which is an hour spent learning what the run already knew.
        return new Refused(Failure.NEVER_BECAME_READY, handle.identifier()
                + " was still running and not ready after " + readinessDeadline.toMillis()
                + " milliseconds; what it wrote is in " + handle.capturedOutput()
                + " and ends: " + endOf(capturedOutput(handle)));
    }

    /** How much of what a container wrote travels with a refusal about it. */
    private static final int KEPT_CHARACTERS = 1500;

    /**
     * The end of what a container wrote, which is where a startup that never finished says why.
     *
     * @param written everything it wrote
     * @return the last of it, on one line
     */
    private static String endOf(String written) {
        final String flattened = written.replaceAll("\\s+", " ").strip();
        return flattened.length() <= KEPT_CHARACTERS ? flattened
                : "..." + flattened.substring(flattened.length() - KEPT_CHARACTERS);
    }

    /**
     * Stops a container through the handle that started it, and waits for it to be gone.
     *
     * <p>Nothing here looks a container up by name. A name can be taken by a replacement, and a
     * cleanup that looked one up would stop a container it never started.</p>
     *
     * @param handle the handle that retains the container
     * @return what the engine did
     */
    public ProcessRun stop(ContainerHandle handle) {
        return stop(handle.identifier());
    }

    /**
     * How much of a container's output this harness keeps.
     *
     * @return the bound, which the harness's own values file declares
     */
    public long maximumCapturedBytes() {
        return maximumCapturedBytes;
    }

    /**
     * Every container this harness labelled that is still running.
     *
     * @return the identifiers, which is empty when a suite left nothing behind
     */
    public List<String> leaked() {
        final ProcessRun listed = run(List.of(engine, "ps", "--quiet",
                "--filter", "label=harness=" + HARNESS_LABEL));
        return listed.output().lines()
                .map(String::strip)
                .filter(identifier -> !identifier.isEmpty())
                .toList();
    }

    /**
     * What a container has written so far, bounded.
     *
     * @param handle the handle that retains it
     * @return its output, at most the declared bound
     */
    public String capturedOutput(ContainerHandle handle) {
        capture(handle);
        return ProcessRun.read(handle.capturedOutput(), maximumCapturedBytes);
    }

    private void capture(ContainerHandle handle) {
        final ProcessRun logs = run(List.of(engine, "logs", handle.identifier()));
        try {
            final byte[] bytes = logs.output().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final int kept = (int) Math.min(bytes.length, maximumCapturedBytes);
            Files.write(handle.capturedOutput(), java.util.Arrays.copyOf(bytes, kept));
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private boolean isRunning(ContainerHandle handle) {
        return isRunning(handle.identifier());
    }

    private boolean isRunning(String identifier) {
        final ProcessRun inspected = run(List.of(engine, "inspect", "--format", "{{.State.Running}}",
                identifier));
        return inspected.succeeded() && "true".equals(inspected.output().strip());
    }

    private Optional<Integer> mappedPort(String identifier, int containerPort) {
        final ProcessRun published = run(List.of(engine, "port", identifier,
                containerPort + "/tcp"));
        if (!published.succeeded()) {
            return Optional.empty();
        }
        final String line = published.output().strip();
        final int colon = line.lastIndexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(line.substring(colon + 1).strip()));
        } catch (final NumberFormatException notAPort) {
            return Optional.empty();
        }
    }

    private ProcessRun stop(String identifier) {
        // Stopping is bounded by the grace it was given rather than by the readiness deadline. A
        // suite that declares a short deadline to prove what a deadline does would otherwise cut
        // the engine off part-way through a stop, and what it left behind is a container still
        // shutting down that the next suite then competes with for the machine.
        final Duration bound = stopGrace.multipliedBy(STOP_BOUND_MULTIPLE);
        ProcessRun.of(bound, maximumCapturedBytes, List.of(engine, "stop", "--time",
                String.valueOf(stopGrace.toSeconds()), identifier));
        final ProcessRun removed = ProcessRun.of(bound, maximumCapturedBytes,
                List.of(engine, "rm", "--force", identifier));
        awaitGone(identifier, bound);
        return removed;
    }

    /**
     * Waits until the engine no longer holds a container at all.
     *
     * <p>Removal is asked for and then waited on, because the engine answers before a container's
     * network is torn down and the next container to publish a port competes with what is left. A
     * cleanup that returned early would make every suite after it depend on how quickly somebody
     * else's machine finished a job nobody was waiting for.</p>
     */
    private void awaitGone(String identifier, Duration bound) {
        final long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline && exists(identifier)) {
            pause();
        }
    }

    private boolean exists(String identifier) {
        return ProcessRun.of(stopGrace, maximumCapturedBytes,
                List.of(engine, "container", "exists", identifier)).succeeded();
    }

    /** How much longer than the grace itself a stop may take before it is abandoned. */
    private static final int STOP_BOUND_MULTIPLE = 2;

    private void pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Creates a network containers on it reach one another by name on.
     *
     * @param name the network's own name
     * @return what the engine did
     */
    public ProcessRun createNetwork(String name) {
        return run(List.of(engine, "network", "create", "--label",
                "harness=" + HARNESS_LABEL, name));
    }

    /**
     * Removes a network, once nothing is attached to it.
     *
     * @param name the network's own name
     * @return what the engine did
     */
    public ProcessRun removeNetwork(String name) {
        return ProcessRun.of(stopGrace.multipliedBy(STOP_BOUND_MULTIPLE), maximumCapturedBytes,
                List.of(engine, "network", "rm", "--force", name));
    }

    private ProcessRun run(List<String> command) {
        return ProcessRun.of(readinessDeadline, maximumCapturedBytes, command);
    }

    private static Path captureFile(String identifier) {
        try {
            return Files.createTempFile("slingshot-agent-" + identifier.substring(0,
                    Math.min(identifier.length(), IDENTIFIER_PREFIX_LENGTH)), ".log");
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** How much of a container identifier is enough to recognise a capture file by. */
    private static final int IDENTIFIER_PREFIX_LENGTH = 12;

    private static TomlParseResult parse(Path values) {
        try {
            final TomlParseResult parsed = Toml.parse(values);
            if (!parsed.errors().isEmpty()) {
                throw new IllegalStateException(values + " does not parse: " + parsed.errors());
            }
            return parsed;
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String required(TomlParseResult values, String key) {
        return Optional.ofNullable(values.getString(key))
                .orElseThrow(() -> new IllegalStateException("the harness declares no " + key));
    }

    private static long requiredNumber(TomlParseResult values, String key) {
        return Optional.ofNullable(values.getLong(key))
                .orElseThrow(() -> new IllegalStateException("the harness declares no " + key));
    }

    private static Duration milliseconds(TomlParseResult values, String key) {
        return Duration.ofMillis(requiredNumber(values, key));
    }

    // Declared where it matters and absent where it does not: the fixtures that prove what a
    // deadline does declare one deadline, and a required second would make every one of them
    // restate a bound they are not about.
    private static Duration millisecondsOr(TomlParseResult values, String key, Duration fallback) {
        final Long declared = values.getLong(key);
        return declared == null ? fallback : Duration.ofMillis(declared);
    }
}
