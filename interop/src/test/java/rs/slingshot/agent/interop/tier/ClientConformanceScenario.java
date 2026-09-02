// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The tier that proves the two halves of this product speak to one another.
 *
 * <p>Nobody has supplied a client executable in this repository and nobody will: it is built from
 * the sibling repository at a named commit, and this side never fetches one. So what runs here is
 * the half that can run — the three refusals, each distinct, each naming what its holder has to do
 * — and the conformance exchange itself is what an owner gets when they supply one.</p>
 *
 * <p>That is deliberate rather than a gap. A suite that quietly did not run would be a suite
 * reporting success it did not earn, and a conformance claim about a build nobody ran is not a
 * claim at all.</p>
 */
final class ClientConformanceScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** Where an instance would be listening, for the profile this tier writes. */
    private static final String ADDRESS = "http://localhost:8080";

    @Test
    @DisplayName("with no client supplied, this tier refuses rather than quietly not running")
    void withNoClientSuppliedThisTierRefuses() {
        final Optional<ClientTier.Refusal> refused = ClientTier.refusal(REPOSITORY);
        assertTrue(refused.isPresent(),
                "a client executable is present in this repository, which is a thing that should"
                        + " never be committed here");
        assertEquals(ClientTier.Refusal.EXECUTABLE_ABSENT, refused.get(),
                "the refusal is not the one an empty repository produces");
        final String said = ClientTier.whatToDo(REPOSITORY, refused.get());
        assertTrue(said.contains(".client/slingshot"), said);
        assertTrue(said.contains("never fetches"), said);
    }

    @Test
    @DisplayName("an absent executable, a wrong digest, and no acknowledgement refuse distinctly")
    void thethreeWaysOfNotBeingReadyRefuseDistinctly(@TempDir Path scratch) throws IOException {
        final Path client = scratch.resolve("slingshot");
        Files.writeString(client, "not really a client", StandardCharsets.UTF_8);
        assertEquals(ClientTier.Refusal.EXECUTABLE_ABSENT,
                refusalOf(scratch, values(scratch, "absent-executable", "", "", false)),
                "an absent executable was not refused as one");
        assertEquals(ClientTier.Refusal.EXECUTABLE_DIFFERS,
                refusalOf(scratch, values(scratch, "slingshot", "", "", false)),
                "an executable with no recorded digest was accepted");
        assertEquals(ClientTier.Refusal.EXECUTABLE_DIFFERS,
                refusalOf(scratch, values(scratch, "slingshot",
                        "0000000000000000000000000000000000000000000000000000000000000000", "",
                        false)),
                "an executable whose digest is not the recorded one was accepted");
        assertEquals(ClientTier.Refusal.NOT_ACKNOWLEDGED,
                refusalOf(scratch, values(scratch, "slingshot", digestOf(client), COMMIT, false)),
                "an unacknowledged executable was run");
        assertEquals(Optional.empty(),
                ClientTier.refusalIn(scratch,
                        values(scratch, "slingshot", digestOf(client), COMMIT, true)),
                "an arrangement with everything in place was refused");
    }

    @Test
    @DisplayName("a pinning naming a version or a range rather than a commit is refused")
    void apinningNamingAversionIsRefused(@TempDir Path scratch) throws IOException {
        final Path client = scratch.resolve("slingshot");
        Files.writeString(client, "not really a client", StandardCharsets.UTF_8);
        assertEquals(ClientTier.Refusal.COMMIT_UNRECORDED,
                refusalOf(scratch, values(scratch, "slingshot", digestOf(client), "0.1.0", true)),
                "a version was accepted where a commit is required");
        assertEquals(ClientTier.Refusal.COMMIT_UNRECORDED,
                refusalOf(scratch, values(scratch, "slingshot", digestOf(client), ">=0.1.0", true)),
                "a range was accepted where a commit is required");
        assertFalse(ClientTier.isAcommit("0.1.0"));
        assertFalse(ClientTier.isAcommit(COMMIT.substring(1)));
        assertTrue(ClientTier.isAcommit(COMMIT));
    }

    @Test
    @DisplayName("the committed pinning names an exact commit, and this build claims only that one")
    void thecommittedPinningNamesAnExactCommit() {
        final TomlParseResult pinned = parse(REPOSITORY.resolve(ClientTier.VALUES_FILE));
        assertTrue(ClientTier.isAcommit(String.valueOf(pinned.getString("client.commit"))),
                "the pinning names something other than an exact commit, so a result from this"
                        + " tier could not say what it is about");
        assertFalse(Boolean.TRUE.equals(pinned.getBoolean("acknowledgement.acknowledged")),
                "this repository acknowledges running somebody else's executable on their behalf,"
                        + " which is a statement only its holder can make");
        assertEquals("", String.valueOf(pinned.getString("executable.digest")),
                "a digest is recorded for an executable this repository does not have");
    }

    @Test
    @DisplayName("the client is configured through its own profile mechanism and nothing else")
    void theclientIsConfiguredThroughItsOwnMechanism(@TempDir Path scratch) throws IOException {
        final Path root = ClientTier.configured(parse(REPOSITORY.resolve(ClientTier.VALUES_FILE)),
                scratch, ADDRESS);
        assertEquals(scratch.resolve(".config").resolve("slingshot"), root,
                "the configuration is not where the client's own contract says it looks");
        final String profile = Files.readString(
                root.resolve("profiles").resolve("interoperability-tier.toml"),
                StandardCharsets.UTF_8);
        assertTrue(profile.contains("base_address = \"" + ADDRESS + "\""), profile);
        assertTrue(profile.contains("format_version = 1"), profile);
        final String selection = Files.readString(root.resolve("selection.toml"),
                StandardCharsets.UTF_8);
        assertTrue(selection.contains("profile = \"interoperability-tier\""), selection);
        assertTrue(selection.contains("environment = \"development\""), selection);
        try (var written = Files.walk(root)) {
            assertEquals(2, written.filter(Files::isRegularFile).count(),
                    "this tier wrote something besides the client's own two documents");
        }
    }

    /** The commit the committed pinning names, which every fixture here reuses. */
    private static final String COMMIT = "ee6013218f6213e3c0bce66b1b917522db1552d1";

    private static ClientTier.Refusal refusalOf(Path root, Path values) {
        return ClientTier.refusalIn(root, values).orElseThrow(
                () -> new IllegalStateException("the arrangement at " + values + " was accepted"));
    }

    private static Path values(Path scratch, String executable, String digest, String commit,
                               boolean acknowledged) throws IOException {
        final Path values = scratch.resolve(executable + "-" + digest.length() + "-"
                + commit.length() + "-" + acknowledged + ".toml");
        Files.writeString(values, "[client]\n"
                + "origin = \"https://github.com/koraytaylan/slingshot\"\n"
                + "commit = \"" + commit + "\"\n"
                + "reason = \"a fixture\"\n\n"
                + "[executable]\n"
                + "path = \"" + executable + "\"\n"
                + "digest = \"" + digest + "\"\n"
                + "reason = \"a fixture\"\n\n"
                + "[acknowledgement]\n"
                + "acknowledged = " + acknowledged + "\n"
                + "statement = \"\"\n"
                + "reason = \"a fixture\"\n\n"
                + "[configuration]\n"
                + "root_components = [\".config\", \"slingshot\"]\n"
                + "profile_directory = \"profiles\"\n"
                + "profile_name = \"interoperability-tier\"\n"
                + "environment = \"development\"\n"
                + "deployment = \"adobe_experience_manager_6_5\"\n"
                + "reason = \"a fixture\"\n", StandardCharsets.UTF_8);
        return values;
    }

    private static String digestOf(Path file) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this runtime has no digest", impossible);
        }
    }

    private static TomlParseResult parse(Path document) {
        try {
            final TomlParseResult parsed = Toml.parse(document);
            assertTrue(parsed.errors().isEmpty(), document + " does not parse: " + parsed.errors());
            return parsed;
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(document + " is not readable", unreadable);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
