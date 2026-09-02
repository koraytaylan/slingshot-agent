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
import rs.slingshot.agent.interop.harness.ContainerHarness;

/**
 * Every attack shape the corpus declares, driven at a running instance.
 *
 * <p>The strongest answer this product has to query injection is that it has no query: every search
 * walks resources through the caller's own resolver. What that leaves is the other two grammars —
 * an address and a repository name — and those are decided by a real resolver rather than by this
 * build's opinion of one.</p>
 *
 * <p>Which is exactly why this is here. A check that disagrees with the resolver about where a path
 * points is a check about a different path, and the disagreement only shows up against a real
 * one.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class InjectionScenario {

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
                PublicSlingTier.start(REPOSITORY, IMAGE, builtBundle());
        tier = assertInstanceOf(InteropTier.Running.class, outcome,
                "the tier did not come up: " + outcome).tier();
    }

    @AfterAll
    void leaveNothingBehind() {
        if (tier != null) {
            tier.stop();
        }
        assertEquals(List.of(), ContainerHarness.at(REPOSITORY).leaked(),
                "the tier left a container running");
    }

    @Test
    @DisplayName("the corpus is closed and grouped by the grammar each shape attacks")
    void thecorpusIsGroupedByGrammar() {
        final String corpus = read(REPOSITORY.resolve("policy/injection-corpus.toml"));
        List.of("query", "path", "name", "expression", "control").forEach(grammar ->
                assertTrue(corpus.contains("grammar = \"" + grammar + "\""),
                        grammar + " is a grammar a value can attack and nothing attacks it"));
        assertTrue(corpus.contains("%252e%252e%252f"),
                "the doubly-encoded traversal is not in the corpus, and it is the one that gets"
                        + " past the fix somebody applies to the singly-encoded one");
    }

    @Test
    @DisplayName("no corpus value reaches anything through the route that starts work")
    void nocorpusValueGetsThrough() {
        final List<String> accepted = new ArrayList<>();
        for (final String shape : shapes()) {
            final int status = requests.postAsAuthenticatedUser(tier.address() + SUBMIT,
                    "{\"command_wire_name\":\"query_paths\",\"root_path\":\"" + escaped(shape)
                            + "\"}", "application/json").statusCode();
            if (status < BAD_REQUEST) {
                accepted.add(shape + " answered " + status);
            }
        }
        assertEquals(List.of(), accepted,
                "a submission carrying an attack shape was accepted rather than refused: "
                        + accepted);
    }

    @Test
    @DisplayName("the route refuses a caller who authenticated as nobody, shape or no shape")
    void therouteRefusesNobody() {
        assertEquals(UNAUTHENTICATED,
                requests.postAsNobody(tier.address() + SUBMIT, "{}", "application/json")
                        .statusCode());
    }

    /**
     * Every shape the corpus declares, read from the corpus rather than restated here.
     *
     * @return the values
     */
    private static List<String> shapes() {
        return read(REPOSITORY.resolve("policy/injection-corpus.toml")).lines()
                .filter(line -> line.startsWith("value = "))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .toList();
    }

    /**
     * One shape, made safe to put inside a document rather than made safe to use.
     *
     * @param shape the attack shape
     * @return the same shape, quoted for the document that carries it
     */
    private static String escaped(String shape) {
        return shape.replace("\\", "\\\\").replace("\"", "\\\"");
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
                    .filter(file -> !String.valueOf(file.getFileName()).contains("sources"))
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
