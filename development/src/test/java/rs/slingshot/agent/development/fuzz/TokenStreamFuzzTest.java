// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.continuation.ContinuationToken;

/**
 * The two values a caller supplies that decide where this side reads from, and the stream they
 * come back out of.
 *
 * <p>A forged token that validated would be a caller reading somebody else's results, which is a
 * different failure from a malformed one being accepted and a very much worse one. So the property
 * is not well-formedness: nothing validates that this authority did not issue, and the target
 * proves that by issuing under a key the authority does not hold rather than by hoping a mutation
 * lands there.</p>
 */
final class TokenStreamFuzzTest {

    @Test
    @DisplayName("nothing validates that this authority did not issue, over the whole corpus")
    void noforgedTokenValidates() {
        assertEquals(List.of(), CorpusRun.findings("continuation-token",
                        new ContinuationTokenTarget()),
                "something validated that this authority did not issue, which is a caller reading"
                        + " somebody else's results");
    }

    @Test
    @DisplayName("there are six refusal categories and no seventh is reachable")
    void thereAreSixRefusalCategories() {
        assertEquals(6, ContinuationToken.Refusal.values().length,
                "the set of refusals changed, and a seventh is one the client has no branch for");
    }

    @Test
    @DisplayName("no resumption identifier reaches another operation's or generation's events")
    void noidentifierReachesAnother() {
        assertEquals(List.of(), CorpusRun.findings("resumption-identifier",
                        new ResumptionIdentifierTarget()));
    }

    @Test
    @DisplayName("every encoded event decodes to what it encoded and crosses no bound")
    void theencoderRoundTripsWithinItsBounds() {
        assertEquals(List.of(), CorpusRun.findings("event-encoder", new EventEncoderTarget()),
                "an event did not come back as it went in, or crossed a bound — either of which"
                        + " ends a stream mid-flight with nothing a client can act on");
    }

    @Test
    @DisplayName("the forgery corpus covers the mutation kinds it declares")
    void theforgeryCorpusIsExhaustiveOverItsKinds() {
        final List<String> rendered = CorpusRun.corpusOf("continuation-token").stream()
                .map(input -> new String(input, java.nio.charset.StandardCharsets.UTF_8))
                .toList();
        assertTrue(rendered.stream().anyMatch(input -> input.contains("0000000000")),
                "no entry mutates the integrity value, which is the first thing a forger tries");
        assertTrue(rendered.stream().anyMatch(input -> input.contains("ffffffffff")),
                "no entry is signed under a key this authority does not hold");
        assertTrue(rendered.contains(""), "no entry is empty");
    }
}
