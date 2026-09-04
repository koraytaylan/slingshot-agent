// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * What is true of every command that changes something, on a running instance.
 *
 * <p>Each command's own scenario proves that command. This proves the properties that are only
 * interesting across all of them, and it is the one that would catch the twentieth command quietly
 * behaving unlike the first nineteen.</p>
 *
 * <p>Every row is selected by what it declares rather than by where its handler lives. A suite that
 * took every {@code write} row would demand a repository commit from a command that stops a bundle,
 * and one that took a package would stop selecting the day somebody moved a class. The declaration
 * is the one thing that says what kind of change a command makes, and it is the thing the client
 * reads too.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class MutationSafetyScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on, spelled by the committed table and by nothing here. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    /** What a submission this build will not act on is answered with. */
    private static final int REFUSED = 400;

    /** The category a command that changes the caller's own repository declares. */
    private static final String REPOSITORY_OUTCOME = "mutation_outcome_unknown";

    /** The category a command that offers something outside this instance declares. */
    private static final String ADMISSION_OUTCOME = "admission_outcome_unknown";

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
    @DisplayName("every command that changes something declares exactly one kind of change")
    void everychangingCommandDeclaresOneKind() {
        final List<String> confused = new ArrayList<>();
        for (final Path row : registryRows()) {
            final String declared = read(row);
            if (declared.contains(REPOSITORY_OUTCOME) && declared.contains(ADMISSION_OUTCOME)) {
                confused.add(String.valueOf(row.getFileName()));
            }
        }
        assertEquals(List.of(), confused, "a command declares that it changes both the caller's own"
                + " repository and a machine this agent cannot observe, which is two commands");
        assertTrue(!changing().isEmpty(),
                "no command declares that it changes anything, so this suite is proving nothing");
    }

    @Test
    @DisplayName("every command that changes something requires an operation key")
    void everychangingCommandRequiresAKey() {
        final List<String> keyless = changing().stream()
                .filter(row -> !read(row).contains("operation_key = \"required\""))
                .map(row -> String.valueOf(row.getFileName()))
                .toList();
        assertEquals(List.of(), keyless, "a command that changes something does not require an"
                + " operation key, so a resent submission would have a second effect");
    }

    @Test
    @DisplayName("every command that changes something answers within one result bound")
    void everychangingCommandStatesItsBound() {
        final List<String> unbounded = changing().stream()
                .filter(row -> !read(row).contains("result_bytes = "))
                .map(row -> String.valueOf(row.getFileName()))
                .toList();
        assertEquals(List.of(), unbounded, "a command that changes something states no bound on"
                + " what it may answer, so an oversized result would be discovered while holding"
                + " it");
    }

    @Test
    @DisplayName("the one command that offers rather than writes declares no repository commit")
    void theadmissionOwesNoCommit() {
        final List<Path> admissions = registryRows().stream()
                .filter(row -> read(row).contains(ADMISSION_OUTCOME))
                .toList();
        assertTrue(!admissions.isEmpty(),
                "no command declares an admission, and the distinction this suite exists to hold"
                        + " is between a repository write and an offer to a machine nobody here"
                        + " can observe");
        admissions.forEach(row -> assertTrue(!read(row).contains(REPOSITORY_OUTCOME),
                row.getFileName() + " declares a repository commit as well as an admission, and a"
                        + " wrapper reading that row would demand a write nobody asked for"));
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity");
    }

    @Test
    @DisplayName("a submission naming a changing command and nothing else is refused before it runs")
    void asubmissionWithNoArgumentIsRefused() {
        for (final Path row : changing()) {
            final String name = String.valueOf(row.getFileName());
            final String command = name.substring(0, name.length() - ".toml".length());
            assertEquals(REFUSED, requests.postAsAuthenticatedUser(tier.address() + SUBMIT,
                            "{\"command_wire_name\":\"" + command + "\"}", "application/json")
                    .statusCode(),
                    command + " accepted a submission carrying nothing but a name, and a command"
                            + " that changes something must refuse before it changes anything");
        }
    }

    private static List<Path> changing() {
        return registryRows().stream()
                .filter(row -> read(row).contains(REPOSITORY_OUTCOME)
                        || read(row).contains(ADMISSION_OUTCOME))
                .toList();
    }

    private static List<Path> registryRows() {
        try (Stream<Path> held = Files.list(REPOSITORY.resolve("policy/commands"))) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(".toml"))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Path builtBundle() {
        final Path target = REPOSITORY.resolve("core/target");
        try (var files = Files.list(target)) {
            return files.filter(file -> String.valueOf(file.getFileName()).endsWith(".jar"))
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no bundle was built at " + target + "; run the reactor build first"));
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
