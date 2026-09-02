// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The tier that needs an input this repository is not allowed to have.
 *
 * <p>The Adobe Experience Manager quickstart jar is licensed to its holder: never committed, never
 * cached here, never published, never fetched. Its absence refuses this tier explicitly rather than
 * skipping it, because a suite that quietly does not run is a suite reporting success it did not
 * earn — and the whole reason the two-bundle split exists is so that the tier which <em>can</em> run
 * everywhere proves as much as possible before this one is needed at all.</p>
 *
 * <p>Three refusals, each distinct because each has a different thing for its holder to do: the jar
 * is not there, the jar is not the one they recorded, or they have not said they know an image is
 * about to be built from it.</p>
 */
public final class QuickstartTier implements InteropTier {

    /** The tier's own letter, as the tier inventory gives it. */
    public static final String NAME = "b";

    private static final String VALUES_FILE = "support/quickstart-tier.toml";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final String address;

    private QuickstartTier(String address) {
        this.address = address;
    }

    /** Why this tier will not run, each with a different thing for the jar's holder to do. */
    public enum Refusal {
        /** The jar is not where the values say it is, and nothing here fetches one. */
        JAR_ABSENT,
        /** The jar is there and is not the one whose digest the owner recorded. */
        JAR_DIFFERS,
        /** The owner has not said they know an image is about to be built from their jar. */
        NOT_ACKNOWLEDGED
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
        final Path jar = root.resolve(String.valueOf(values.getString("jar.path")));
        if (!Files.isRegularFile(jar)) {
            return Optional.of(Refusal.JAR_ABSENT);
        }
        final String recorded = String.valueOf(values.getString("jar.digest"));
        if (recorded.isBlank() || !recorded.equals(digestOf(jar))) {
            return Optional.of(Refusal.JAR_DIFFERS);
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
        final String jar = String.valueOf(values.getString("jar.path"));
        return switch (refusal) {
            case JAR_ABSENT -> "no quickstart jar at " + jar + ". This tier never fetches one: the"
                    + " jar is licensed to its holder, so put your own there and record its digest"
                    + " in " + VALUES_FILE + ".";
            case JAR_DIFFERS -> "the jar at " + jar + " is not the one whose digest "
                    + VALUES_FILE + " records. Record the digest of the jar you have, or put back"
                    + " the one you recorded.";
            case NOT_ACKNOWLEDGED -> "the jar's holder has not acknowledged that a container image"
                    + " will be built from it locally. Set acknowledgement.acknowledged in "
                    + VALUES_FILE + ". Nothing here can make that statement for them.";
        };
    }

    /**
     * Starts the tier, or refuses with what its holder should do about it.
     *
     * @param root the repository root
     * @return the running tier, or the one reason there is none
     */
    public static Outcome start(Path root) {
        return startIn(root, root.resolve(VALUES_FILE));
    }

    /**
     * Starts the tier against values from wherever they sit.
     *
     * @param root the directory the values' own paths are relative to
     * @param valuesFile the values document
     * @return the running tier, or the one reason there is none
     */
    public static Outcome startIn(Path root, Path valuesFile) {
        final Optional<Refusal> refused = refusalIn(root, valuesFile);
        if (refused.isPresent()) {
            return new Refused(Failure.INPUT_ABSENT, whatToDo(root, refused.get()));
        }
        return new Refused(Failure.INPUT_ABSENT, "this tier is not built in this commit; the image"
                + " assembly from an owner-supplied jar arrives with the plan that needs it");
    }

    /**
     * The tier as it exists where the licensed input is not, which is every machine but its
     * holder's.
     *
     * @return a tier that never started and answers nothing
     */
    public static QuickstartTier notRunning() {
        return new QuickstartTier("");
    }

    /**
     * Where the values say the owner's own jar goes.
     *
     * @param root the repository root
     * @return the path, which version control ignores
     */
    public static Path jarPath(Path root) {
        return root.resolve(String.valueOf(parse(root.resolve(VALUES_FILE)).getString("jar.path")));
    }

    /**
     * The digest of a file, in the form the values record.
     *
     * @param file the file to digest
     * @return the lower-case hexadecimal digest
     */
    public static String digestOf(Path file) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(DIGEST_ALGORITHM).digest(Files.readAllBytes(file)));
        } catch (final NoSuchAlgorithmException absent) {
            throw new IllegalStateException("this runtime provides no " + DIGEST_ALGORITHM, absent);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public HttpResponse<String> readAsAuthenticatedUser(String path) {
        throw new IllegalStateException("this tier is not running");
    }

    @Override
    public HttpResponse<String> readAsNobody(String path) {
        throw new IllegalStateException("this tier is not running");
    }

    @Override
    public Optional<String> bundleState(String symbolicName) {
        return Optional.empty();
    }

    /**
     * Everything the running instance wrote while this tier held it.
     *
     * @return what it wrote, bounded by what the harness captures
     */
    @Override
    public String capturedOutput() {
        // Nothing was started, so nothing was written. This tier refuses before it holds an
        // instance, and an empty answer is what an instance that never ran wrote.
        return "";
    }

    @Override
    public void stop() {
        // Nothing was started, so nothing is stopped. A tier that refused before starting anything
        // has nothing to clean up, and pretending otherwise would hide the refusal.
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
