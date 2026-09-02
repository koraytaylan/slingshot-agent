// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sixty-four argument shapes, derived from the registry rather than listed.
 *
 * <p>Deriving them is the whole point: a list here would be a list somebody edits on the day they
 * are thinking about the command rather than about this, and the command they forget is the one
 * nobody fuzzes.</p>
 */
final class CommandArgumentFuzzTest {

    /** How many commands the client's own table publishes, which this registry matches. */
    private static final int PUBLISHED_COMMANDS = 64;

    @Test
    @DisplayName("every registry row has a target, because the target is derived from the registry")
    void everyrowHasATarget() {
        assertEquals(PUBLISHED_COMMANDS, new CommandArgumentTarget().rows().size(),
                "the registry no longer holds the commands the client publishes, so a command is"
                        + " either unfuzzed or fuzzed and unregistered");
    }

    @Test
    @DisplayName("an input constructs a value or is refused, and never a value past a bound")
    void nothingConstructsPastABound() {
        assertEquals(List.of(), CorpusRun.findings("command-argument", new CommandArgumentTarget()),
                "something read as a value past a bound the contract states, or as an address"
                        + " outside the root it was given");
    }

    @Test
    @DisplayName("the corpus carries the separator, parent-reference and encoding tricks")
    void thecorpusCarriesTheEscapes() {
        final List<String> rendered = CorpusRun.corpusOf("command-argument").stream()
                .map(input -> new String(input, java.nio.charset.StandardCharsets.UTF_8))
                .toList();
        assertTrue(rendered.stream().anyMatch(input -> input.contains("..")),
                "no entry tries a parent reference");
        assertTrue(rendered.stream().anyMatch(input -> input.contains("%2e")),
                "no entry tries an encoded parent reference, which is the one that gets past a"
                        + " check written against the plain spelling");
        assertTrue(rendered.stream().anyMatch(input -> input.contains("//")),
                "no entry tries a doubled separator");
    }

    @Test
    @DisplayName("a weakened validator is found, so the target exercises validation")
    void aweakenedValidatorIsFound() {
        final FuzzTarget weakened = input -> input.length > 1
                ? FuzzOutcome.broken("a deliberately weakened validator",
                        "a command that accepted an address it declares no root for")
                : FuzzOutcome.held();
        assertTrue(!CorpusRun.findings("command-argument", weakened).isEmpty(),
                "the corpus found nothing wrong with a validator that is wrong, which means the"
                        + " target exercises construction rather than validation");
    }
}
