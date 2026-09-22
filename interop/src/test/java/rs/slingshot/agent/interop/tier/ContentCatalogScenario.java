// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The four catalogues that list what an author can place.
 *
 * <p>What a unit suite proves is which node matches. What it cannot prove is that each command is
 * registered, refuses an operation key, and declares a query an index already covers.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class ContentCatalogScenario {

    private static final Path REPOSITORY = repositoryRoot();

    /** The route work is submitted on. */
    private static final String SUBMIT = "/bin/slingshot/agent/submit";

    /** The pinned public image, at the digest the preparation command recorded. */
    private static final String IMAGE = "localhost/slingshot-agent-public-sling:1";

    private static final List<String> COMMANDS = List.of(
            "list_component_definitions",
            "list_components",
            "list_content_fragments",
            "list_experience_fragments");

    /** What a caller who presented no identity is answered with. */
    private static final int UNAUTHENTICATED = 401;

    private final TierRequests requests = TierRequests.open();

    private InteropTier tier;

    @BeforeAll
    void install() {
        final InteropTier.Outcome outcome =
                SharedPublicSlingTier.get(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @Test
    @DisplayName("each row refuses an operation key, which the client's own table says")
    void eachRowRefusesAnOperationKey() {
        for (final String command : COMMANDS) {
            assertTrue(row(command).contains("operation_key = \"refused\""),
                    command + " no longer refuses an operation key");
        }
    }

    @Test
    @DisplayName("each catalogue declares a query an index can answer")
    void eachCatalogueDeclaresAnIndexedQuery() {
        final String coverage = read(REPOSITORY.resolve("policy/query-index-coverage.toml"));
        for (final String command : COMMANDS) {
            assertTrue(coverage.contains("issued_by = \"" + command + "\""),
                    command + " declares no query");
        }
    }

    @Test
    @DisplayName("the route that starts work refuses a caller who authenticated as nobody")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode(),
                "work was started for a caller who presented no identity");
    }

    private static String row(String command) {
        return read(REPOSITORY.resolve("policy/commands/" + command + ".toml"));
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
