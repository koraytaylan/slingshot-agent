// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether this console can be read and operated by somebody not using a mouse or English.
 *
 * <p>Both of these are cheap now and expensive later, and both are decidable from the committed
 * markup because Granite renders on the server. The one that keeps the others true is the literal
 * rule: a dictionary is complete only while nothing bypasses it, and what bypasses it is always one
 * string somebody typed in a hurry.</p>
 */
final class ConsoleAccessibilityTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/console-accessibility");

    @Test
    @DisplayName("this console is readable and operable, and its dictionary matches both ways")
    void thisConsoleIsReadableAndOperable() {
        assertEquals("", ConsoleAccessibility.across(REPOSITORY).render());
    }

    @Test
    @DisplayName("a control nothing names is refused, naming the control")
    void anunlabelledControlIsRefused() {
        assertRule("unlabelled-control.content.xml", ConsoleAccessibility.ACCESSIBLE_NAME,
                "submit");
    }

    @Test
    @DisplayName("a sentence where a key belongs is refused, quoting the sentence")
    void aliteralStringIsRefused() {
        assertRule("literal-string.content.xml", ConsoleAccessibility.LITERAL_STRING,
                "Operations");
    }

    @Test
    @DisplayName("a table whose cells belong to no header is refused")
    void aheaderlessTableIsRefused() {
        assertRule("headerless-table.content.xml", ConsoleAccessibility.HEADER_ASSOCIATION,
                "readings");
    }

    @Test
    @DisplayName("an image with nothing to read instead of it is refused")
    void animageWithoutATextAlternativeIsRefused() {
        assertRule("imageless-alternative.content.xml", ConsoleAccessibility.TEXT_ALTERNATIVE,
                "badge");
    }

    @Test
    @DisplayName("a control only a pointing device reveals is refused")
    void ahoverOnlyControlIsRefused() {
        assertRule("hover-only-control.content.xml", ConsoleAccessibility.HOVER_ONLY, "actions");
    }

    @Test
    @DisplayName("a key used and not declared and one declared and not used are two findings")
    void thetwoDictionaryDirectionsAreDistinct() {
        assertEquals(2, List.of(ConsoleAccessibility.KEY_NOT_DECLARED,
                        ConsoleAccessibility.KEY_NOT_USED).stream().distinct().count(),
                "the two directions are reported under one rule, and they are two different"
                        + " mistakes: a string nobody translated, and a translation nothing shows");
        assertTrue(ConsoleAccessibility.declaredKeys(REPOSITORY
                        .resolve(ConsoleAccessibility.DICTIONARY)).size() > 1,
                "the dictionary declares nothing, so both directions would agree about nothing");
    }

    private static void assertRule(String fixture, String rule, String named) {
        final Set<String> used = new LinkedHashSet<>();
        final List<PolicyFinding> findings =
                ConsoleAccessibility.inFile(fixture, FIXTURES.resolve(fixture), used);
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }
}
