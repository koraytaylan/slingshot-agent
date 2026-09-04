// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Everywhere a value leaves, driven at once rather than one surface at a time.
 *
 * <p>Two audits already cover the two places anybody thinks of. What is left is the four that leave
 * without anybody watching: a log line goes to a file somebody else reads, an event goes down a
 * stream that is not a response, an artifact is bytes nobody re-reads, and a property this agent
 * wrote sits in the repository until somebody looks.</p>
 *
 * <p>The one that matters most is the key ring, and the claim about it is absolute rather than
 * conditional: it is not in a response, a log, an event, an artifact, a health check, or the
 * console, under any request at all, including under every failure.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CredentialExposureScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** The first status that is a refusal rather than an answer. */
    private static final int BAD_REQUEST = 400;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        // The shared runtime stays for the scenario after this one and goes when the test runtime
        // ends. What has to hold here is that nothing else was left behind.
        assertEquals(List.of(), SharedPublicSlingTier.leftBeside(REPOSITORY),
                "something other than the shared runtime was left running");
    }

    @Test
    @DisplayName("no planted value comes back from the route that starts work, under any failure")
    void noplantedValueComesBack() {
        final List<String> leaked = new ArrayList<>();
        for (final String planted : plantedValues()) {
            final String body = requests.postAsAuthenticatedUser(tier.address() + SUBMIT,
                    "{\"command_wire_name\":\"query_paths\"}", "application/json").body();
            if (body.contains(planted)) {
                leaked.add(planted);
            }
        }
        assertEquals(List.of(), leaked,
                "a refusal carried a value the corpus plants, and a refusal is where one gets out:"
                        + " it is the answer written in a hurry by whoever was explaining what went"
                        + " wrong: " + leaked);
    }

    @Test
    @DisplayName("the corpus plants a value of every kind, so scanning for one means something")
    void everykindIsPlanted() {
        final String corpus = read(REPOSITORY.resolve("policy/redaction-corpus.toml"));
        List.of("credential", "token", "key", "repository-path", "internal-name", "queue-or-topic",
                        "transport-address", "configuration-value")
                .forEach(kind -> assertTrue(corpus.contains("kind = \"" + kind + "\""),
                        kind + " is not a kind the corpus declares, so nothing is scanned for it"));
    }

    @Test
    @DisplayName("no response header carries a planted value either")
    void noheaderCarriesOne() {
        final List<String> leaked = new ArrayList<>();
        final var answered = requests.postAsNobody(tier.address() + SUBMIT, "{}",
                "application/json");
        answered.headers().map().forEach((name, values) -> plantedValues().stream()
                .filter(planted -> values.stream().anyMatch(value -> value.contains(planted)))
                .forEach(planted -> leaked.add(name + " carried " + planted)));
        assertEquals(List.of(), leaked,
                "a header carried a planted value, which is the place nobody reads and everybody"
                        + " logs: " + leaked);
    }

    @Test
    @DisplayName("the route refuses a caller who authenticated as nobody without saying why in detail")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode());
    }

    /**
     * Every value the corpus plants, read from the corpus rather than restated here.
     *
     * @return the values
     */
    private static List<String> plantedValues() {
        return read(REPOSITORY.resolve("policy/redaction-corpus.toml")).lines()
                .filter(line -> line.startsWith("planted = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    // Neither of the archives a release also builds: a javadoc jar handed to the
                    // platform as a bundle is refused with a 500 that reads like the product
                    // failing to install.
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources")
                            && !String.valueOf(file.getFileName()).contains("javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
        } catch (final java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
