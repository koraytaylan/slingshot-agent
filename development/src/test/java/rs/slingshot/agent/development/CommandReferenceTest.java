// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether the committed command reference is what the registry renders.
 *
 * <p>Each rejection is proved on a copy of the committed reference with exactly one thing wrong
 * with it, so a failure names the thing rather than the document. The committed pair is checked
 * whole in the first assertion, which is what makes the others mean something.</p>
 */
final class CommandReferenceTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/command-reference");

    private static final Path REGISTRY =
            REPOSITORY.resolve(CommandReference.REGISTRY_DIRECTORY);

    @Test
    @DisplayName("the committed reference is the registry, row for row and field for field")
    void thereferenceIsTheRegistry() {
        assertEquals("", CommandReference.against(REPOSITORY).render());
        final String table = CommandReference.render(REGISTRY);
        assertEquals(RegistryCompleteness.SIXTYFOUR_COMMANDS + 2,
                table.split("\n").length,
                "the rendered table is not sixty-four rows and a two-line heading");
    }

    @Test
    @DisplayName("rendering twice produces byte-identical output")
    void renderingIsStable() {
        assertEquals(CommandReference.render(REGISTRY), CommandReference.render(REGISTRY),
                "the table renders differently on a second pass, so regenerating it would show a"
                        + " change nobody made");
    }

    @Test
    @DisplayName("regenerating replaces the table and leaves every other byte alone")
    void regeneratingLeavesTheProseAlone() {
        final String committed = read(REPOSITORY.resolve(CommandReference.REFERENCE_FILE));
        final String written = CommandReference.written(committed, CommandReference.render(REGISTRY));
        assertEquals(committed, written,
                "regenerating the committed reference changed it, so the committed one is not what"
                        + " the registry renders");
        final String altered = CommandReference.written(committed, "| altered |");
        assertTrue(altered.contains("Every command this agent answers")
                        && altered.contains("| altered |")
                        && !altered.contains("| `create_page` |"),
                "regenerating with a different table did not leave the hand-written prose alone");
    }

    @Test
    @DisplayName("a missing row, an extra one, and a differing value are three findings")
    void thethreeDivergencesAreDistinct() {
        assertRule(against("missing-row.md"), CommandReference.MISSING_ROW,
                "the reference does not render it");
        assertRule(against("extra-row.md"), CommandReference.EXTRA_ROW,
                "the registry declares nothing that renders to that line");
        assertRule(against("value-differs.md"), CommandReference.MISSING_ROW, "create_page");
        assertRule(against("value-differs.md"), CommandReference.EXTRA_ROW, "create_page");
    }

    @Test
    @DisplayName("a reference with no generated region is refused, because nothing could tell")
    void areferenceWithNoRegionIsRefused() {
        assertRule(against("no-region.md"), CommandReference.NO_REGION,
                "nothing can tell what in it was written by hand");
    }

    @Test
    @DisplayName("every declared category of every command is rendered, in the registry's own order")
    void everycategoryIsRendered() {
        final String table = CommandReference.render(REGISTRY);
        RegistryCompleteness.rowsIn(REGISTRY).keySet().forEach(command -> {
            final String row = read(REGISTRY.resolve(command + ".toml"));
            final java.util.regex.Matcher categories = java.util.regex.Pattern
                    .compile("(?m)^failure_categories = \\[([^\\]]*)\\]$").matcher(row);
            assertTrue(categories.find(), command + " declares no failure categories");
            for (final String category : categories.group(1).split(",")) {
                final String name = category.strip().replace("\"", "");
                assertTrue(name.isEmpty() || table.contains("`" + name + "`"),
                        command + " declares " + name + " and the reference does not render it,"
                                + " so a caller reading the reference cannot handle it");
            }
        });
    }

    @Test
    @DisplayName("what the check does not decide is written down rather than left to silence")
    void whatItDoesNotDecideIsRecorded() {
        assertEquals(2, CommandReference.REVIEW_CHECKLIST.size());
        CommandReference.REVIEW_CHECKLIST.forEach(entry ->
                assertTrue(entry.startsWith("whether"),
                        "a checklist entry is not a question this check leaves open: " + entry));
        assertTrue(CommandReference.REVIEW_CHECKLIST.stream()
                        .anyMatch(entry -> entry.contains("summary")),
                "whether a summary is accurate is not recorded as something this check does not"
                        + " decide, and a check that seemed to judge it would be believed");
    }

    private static String against(String fixture) {
        return CommandReference.against(REGISTRY, FIXTURES.resolve(fixture)).render();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
