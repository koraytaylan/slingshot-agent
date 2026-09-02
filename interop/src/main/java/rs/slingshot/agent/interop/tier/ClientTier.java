// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import rs.slingshot.agent.interop.harness.ContainerHarness;
import rs.slingshot.agent.interop.harness.ProcessRun;

/**
 * The sibling repository's own executable, run against an instance this repository started.
 *
 * <p>The only thing in either repository that proves the two halves speak to one another. Its
 * failures are cross-repository defects rather than local ones, which is why a result names the
 * exact client commit it ran against and claims nothing about any other build: "the client works"
 * would be a claim about builds nobody ran.</p>
 *
 * <p>The client is configured through its own profile mechanism and nothing else — a profile
 * document and a selection document under the configuration root its own contract names, in a
 * scratch home directory this tier owns. A tier that reached in some other way would be proving a
 * path no user takes.</p>
 *
 * <p>The executable is not committed here and is never fetched. Its absence refuses this tier
 * explicitly rather than skipping it, because a suite that quietly does not run is a suite
 * reporting success it did not earn.</p>
 */
public final class ClientTier {

    /** Where the pinning this tier reads is committed. */
    public static final String VALUES_FILE = "support/client-tier.toml";

    /** How long the client is given to answer one invocation. */
    private static final int INVOCATION_SECONDS = 120;


    private final Path executable;
    private final Path configurationRoot;
    private final String commit;
    private final long capturedBytes;

    private ClientTier(Path executable, Path configurationRoot, String commit,
                       long capturedBytes) {
        this.executable = executable;
        this.configurationRoot = configurationRoot;
        this.commit = commit;
        this.capturedBytes = capturedBytes;
    }

    /** Why this tier cannot run, each distinct because each has a different thing to do about it. */
    public enum Refusal {
        /** Nobody has put a client executable where this tier reads one. */
        EXECUTABLE_ABSENT,
        /** The executable is not the one whose digest the pinning records. */
        EXECUTABLE_DIFFERS,
        /** The pinning records no commit, so a result could not say what it is about. */
        COMMIT_UNRECORDED,
        /** The executable's holder has not acknowledged that this repository will run it. */
        NOT_ACKNOWLEDGED
    }

    /** The result of starting the tier: the tier, or the one reason there is none. */
    public sealed interface Outcome permits Ready, Refused {
    }

    /**
     * A tier that can run the client.
     *
     * @param tier the tier
     */
    public record Ready(ClientTier tier) implements Outcome {
    }

    /**
     * No tier, and exactly why.
     *
     * @param refusal why not
     */
    public record Refused(Refusal refusal) implements Outcome {
    }

    /**
     * Whether this tier can run at all, and if not, exactly why.
     *
     * @param root the repository root
     * @return nothing where every precondition is met, or the one that is not
     */
    public static Optional<Refusal> refusal(Path root) {
        return refusalIn(root, root.resolve(VALUES_FILE));
    }

    /**
     * Whether this tier can run against values from wherever they sit.
     *
     * @param root the directory the values' own paths are relative to
     * @param valuesFile the values document
     * @return nothing where every precondition is met, or the one that is not
     */
    public static Optional<Refusal> refusalIn(Path root, Path valuesFile) {
        final TomlParseResult values = parse(valuesFile);
        final Path client = root.resolve(String.valueOf(values.getString("executable.path")));
        if (!Files.isRegularFile(client)) {
            return Optional.of(Refusal.EXECUTABLE_ABSENT);
        }
        final String recorded = String.valueOf(values.getString("executable.digest"));
        if (recorded.isBlank() || !recorded.equals(digestOf(client))) {
            return Optional.of(Refusal.EXECUTABLE_DIFFERS);
        }
        if (!isAcommit(String.valueOf(values.getString("client.commit")))) {
            return Optional.of(Refusal.COMMIT_UNRECORDED);
        }
        if (!Boolean.TRUE.equals(values.getBoolean("acknowledgement.acknowledged"))) {
            return Optional.of(Refusal.NOT_ACKNOWLEDGED);
        }
        return Optional.empty();
    }

    /**
     * What somebody should do about a refusal.
     *
     * @param root the repository root
     * @param refusal the refusal
     * @return the sentence that names what is missing and where it goes
     */
    public static String whatToDo(Path root, Refusal refusal) {
        final TomlParseResult values = parse(root.resolve(VALUES_FILE));
        final String client = String.valueOf(values.getString("executable.path"));
        return switch (refusal) {
            case EXECUTABLE_ABSENT -> "no client executable at " + client + ". This tier never"
                    + " fetches one: build the sibling repository at the commit " + VALUES_FILE
                    + " records, put the executable there, and record its digest.";
            case EXECUTABLE_DIFFERS -> "the executable at " + client + " is not the one whose"
                    + " digest " + VALUES_FILE + " records. Record the digest of the one you"
                    + " built, or put back the one you recorded.";
            case COMMIT_UNRECORDED -> "no client commit is recorded in " + VALUES_FILE + ". A"
                    + " result has to name the exact commit it is about, because a conformance"
                    + " claim about an unnamed build is a claim nobody can reproduce.";
            case NOT_ACKNOWLEDGED -> "the executable's holder has not acknowledged that this"
                    + " repository will run it against a container it started. Set"
                    + " acknowledgement.acknowledged in " + VALUES_FILE + ". Nothing here can make"
                    + " that statement for them.";
        };
    }

    /**
     * Prepares the client to talk to one running instance, through its own profile mechanism.
     *
     * @param root the repository root
     * @param home a directory this tier owns, which becomes the client's home
     * @param address where the instance is listening
     * @return the tier, or the one reason there is none
     */
    public static Outcome start(Path root, Path home, String address) {
        return startIn(root, root.resolve(VALUES_FILE), home, address);
    }

    /**
     * Prepares the client against values from wherever they sit.
     *
     * @param root the directory the values' own paths are relative to
     * @param valuesFile the values document
     * @param home a directory this tier owns, which becomes the client's home
     * @param address where the instance is listening
     * @return the tier, or the one reason there is none
     */
    public static Outcome startIn(Path root, Path valuesFile, Path home, String address) {
        final Optional<Refusal> refused = refusalIn(root, valuesFile);
        if (refused.isPresent()) {
            return new Refused(refused.get());
        }
        final TomlParseResult values = parse(valuesFile);
        final Path configurationRoot = configured(values, home, address);
        return new Ready(new ClientTier(
                root.resolve(String.valueOf(values.getString("executable.path"))),
                configurationRoot, String.valueOf(values.getString("client.commit")),
                // How much of what the client said is kept is the harness's own number, because
                // what this tier keeps of one process's output is what it keeps of any.
                ContainerHarness.at(root).maximumCapturedBytes()));
    }

    /**
     * Writes the client's own configuration into a home directory this tier owns.
     *
     * <p>The profile and the selection are the client's own documents, in the directory its own
     * contract names. Nothing else is set: the exchange has to be the one a user would have.</p>
     *
     * @param values the pinning
     * @param home the home directory
     * @param address where the instance is listening
     * @return the configuration root that was written
     */
    public static Path configured(TomlParseResult values, Path home, String address) {
        final TomlArray components = stated(values.getArray("configuration.root_components"),
                "configuration.root_components");
        final Path root = java.util.stream.IntStream.range(0, components.size())
                .mapToObj(components::getString)
                .reduce(home, Path::resolve, (first, second) -> second);
        final String profile = String.valueOf(values.getString("configuration.profile_name"));
        final String environment = String.valueOf(values.getString("configuration.environment"));
        write(root.resolve(String.valueOf(values.getString("configuration.profile_directory")))
                        .resolve(profile + PROFILE_EXTENSION),
                "format_version = 1\n"
                        + "name = \"" + profile + "\"\n\n"
                        + "[environments." + environment + "]\n"
                        + "deployment = \""
                        + values.getString("configuration.deployment") + "\"\n\n"
                        + "[environments." + environment + ".author]\n"
                        + "base_address = \"" + address + "\"\n\n"
                        + "[environments." + environment + ".authentication]\n"
                        + "method = \"basic\"\n"
                        + "user_name = \"admin\"\n"
                        + "password = \"admin\"\n");
        write(root.resolve("selection.toml"),
                "format_version = 1\n"
                        + "profile = \"" + profile + "\"\n"
                        + "environment = \"" + environment + "\"\n");
        return root;
    }

    /**
     * Runs the client with the arguments a user would give it.
     *
     * @param arguments what to ask it for
     * @return what it said, and how it ended
     */
    public ProcessRun run(List<String> arguments) {
        final List<String> invocation = new java.util.ArrayList<>();
        invocation.add(executable.toString());
        invocation.addAll(arguments);
        return ProcessRun.of(java.time.Duration.ofSeconds(INVOCATION_SECONDS), capturedBytes,
                invocation, java.util.Map.of("HOME", homeOf(configurationRoot)));
    }

    /**
     * The exact client commit a result from this tier is about.
     *
     * @return the commit
     */
    public String commit() {
        return commit;
    }

    /**
     * Where this tier wrote the client's own configuration.
     *
     * @return the configuration root
     */
    public Path configurationRoot() {
        return configurationRoot;
    }

    /** What a profile document is called, which is the client's own extension for one. */
    private static final String PROFILE_EXTENSION = ".toml";

    private static String homeOf(Path configurationRoot) {
        // Two components up from the configuration root, which is what the client's own contract
        // says a home holds: a pinning whose root components are empty would leave nothing to go
        // up from, and that is a defect in the pinning rather than a case to carry.
        return stated(stated(configurationRoot.getParent(),
                        "the configuration root's own parent").getParent(),
                "a home for the configuration root to sit under").toString();
    }

    private static <T> T stated(T value, String what) {
        if (value == null) {
            throw new IllegalStateException(VALUES_FILE + " states no " + what);
        }
        return value;
    }

    /**
     * Whether one value is an exact commit rather than a range or a version.
     *
     * @param recorded what the pinning records
     * @return whether a result may name it
     */
    public static boolean isAcommit(String recorded) {
        return recorded.length() == COMMIT_LENGTH
                && recorded.chars().allMatch(scalar -> scalar >= '0' && scalar <= '9'
                        || scalar >= 'a' && scalar <= 'f');
    }

    /** How long a commit is, which is what tells one from a version or a range. */
    private static final int COMMIT_LENGTH = 40;

    private static void write(Path document, String content) {
        try {
            Files.createDirectories(stated(document.getParent(),
                    "a directory for " + document.getFileName()));
            Files.writeString(document, content, StandardCharsets.UTF_8);
        } catch (final IOException unwritable) {
            throw new UncheckedIOException(document + " could not be written", unwritable);
        }
    }

    private static String digestOf(Path file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (final NoSuchAlgorithmException | IOException unreadable) {
            throw new IllegalStateException(file + " could not be digested", unreadable);
        }
    }

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
}
