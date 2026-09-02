// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three places bytes somebody else produced become values on this side.
 *
 * <p>The reader is fuzzed for what it must never do. The writer is fuzzed from the other direction,
 * because the round trip is what the five-field identity depends on — a value where writing twice
 * produces different bytes is a submission refused in production for no reason either side can
 * see.</p>
 *
 * <p>What runs here is the corpus and a seeded run of mutations of it, which costs nothing and
 * therefore never stops running. The coverage-guided tool runs the same targets over the same
 * corpus for very much longer, and is pinned and verified separately.</p>
 */
final class ParserFuzzTest {

    @Test
    @DisplayName("any bytes at all produce a value or a refusal naming a bound, and never a throw")
    void thereaderAnswersOrRefuses() {
        assertEquals(List.of(), CorpusRun.findings("document-reader", new DocumentReaderTarget()),
                "the reader did something other than answer or refuse, which a hostile sender"
                        + " reads as having found something");
    }

    @Test
    @DisplayName("what the writer writes the reader reads back, and twice is the same bytes")
    void thewriterRoundTripsAndIsIdempotent() {
        assertEquals(List.of(), CorpusRun.findings("canonical-writer",
                        new CanonicalWriterTarget()),
                "a value's canonical bytes are not stable, which is a submission whose derived"
                        + " digest differs from the client's for a reason nobody can see");
    }

    @Test
    @DisplayName("a body that is not the length it declared is refused, framing decided first")
    void thebodyReaderHoldsItsFraming() {
        assertEquals(List.of(), CorpusRun.findings("request-body", new RequestBodyTarget()));
    }

    @Test
    @DisplayName("the corpus is a regression suite: a reintroduced defect is caught by it alone")
    void thecorpusCatchesAReintroducedDefect() {
        final FuzzTarget weakened = input -> input.length > 2
                ? FuzzOutcome.broken("a deliberately reintroduced defect",
                        "a reader that accepted anything longer than two bytes")
                : FuzzOutcome.held();
        assertTrue(!CorpusRun.findings("document-reader", weakened).isEmpty(),
                "the committed corpus found nothing wrong with a target that is wrong, which"
                        + " means the corpus is decoration rather than a regression suite");
    }

    @Test
    @DisplayName("every corpus holds the inputs a vector never would")
    void thecorpusHoldsWhatAVectorNeverWould() {
        final List<String> rendered = CorpusRun.corpusOf("document-reader").stream()
                .map(input -> new String(input, java.nio.charset.StandardCharsets.UTF_8))
                .toList();
        assertTrue(rendered.contains(""), "the corpus holds no empty input");
        assertTrue(rendered.stream().anyMatch(input -> input.contains("\"a\":1,\"a\":2")),
                "the corpus holds no duplicate member, which is the shape a hand-written vector"
                        + " never is and a hostile sender always tries");
        assertTrue(rendered.stream().anyMatch(input -> input.startsWith("[[[[")),
                "the corpus holds nothing deeply nested, which is where an unbounded reader"
                        + " stops being a reader and becomes a stack");
    }
}
