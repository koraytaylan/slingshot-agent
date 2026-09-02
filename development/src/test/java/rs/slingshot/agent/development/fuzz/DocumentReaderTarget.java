// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development.fuzz;

import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Arbitrary bytes through the reader every protocol document arrives by.
 *
 * <p>One property, and it is about what must never happen rather than about what should. Any bytes
 * at all produce either a typed value or a refusal naming why — never a partial value, never an
 * allocation unrelated to the input's own length, and never an exception. The last one matters
 * most: a reader that threw would answer a hostile sender with a server fault, which tells them
 * they found something.</p>
 *
 * <p>A refusal is not a finding. A fuzzer that treated one as a finding would spend its whole
 * budget reporting the bounded reader doing exactly its job.</p>
 */
public final class DocumentReaderTarget implements FuzzTarget {

    /**
     * How much text a read may leave behind, as a multiple of the bytes it was given.
     *
     * <p>A factor rather than a constant is what makes this a property about the input rather than
     * a number somebody tuned. What is measured is the text the value holds — every member name and
     * every string in it — rather than how the value prints, because a printed record carries type
     * names that have nothing to do with the input and would make this a check on the printer.</p>
     *
     * <p>Two, because canonical text is at most the input with its whitespace removed. Anything
     * that allocates in proportion to something other than its input shows up here as a multiple
     * nobody can explain.</p>
     */
    private static final long ALLOCATION_FACTOR = 2;

    /** How the fuzzing tool reaches this target. */
    private static final DocumentReaderTarget TARGET = new DocumentReaderTarget();

    private final BoundedDocumentReader.Bounds bounds;

    /** Holds one target bound by the contract this build authenticated. */
    public DocumentReaderTarget() {
        this.bounds = BoundedDocumentReader.Bounds.from(
                ((AgentContract.Loaded) AgentContract.load()).contract());
    }

    /**
     * The entry point the fuzzing tool calls.
     *
     * @param input arbitrary bytes
     */
    public static void fuzzerTestOneInput(byte[] input) {
        final FuzzOutcome outcome = TARGET.of(input);
        if (outcome instanceof final FuzzOutcome.Broken broken) {
            throw new AssertionError(broken.property() + ": " + broken.detail());
        }
    }

    @Override
    public FuzzOutcome of(byte[] input) {
        final Attempted.Answered<BoundedDocumentReader.Outcome> asked =
                Attempted.of(() -> BoundedDocumentReader.read(input, bounds));
        if (asked.threw()) {
            return FuzzOutcome.broken("the reader answers rather than throws",
                    "it threw " + asked.threwWhat());
        }
        final BoundedDocumentReader.Outcome outcome = asked.value().orElseThrow();
        if (outcome instanceof final BoundedDocumentReader.Refused refused) {
            return refused.refusal() == null
                    ? FuzzOutcome.broken("every refusal names why", "it refused and named nothing")
                    : FuzzOutcome.held();
        }
        final long rendered = textIn(((BoundedDocumentReader.Read) outcome).value());
        return rendered > ALLOCATION_FACTOR * Math.max(input.length, 1L)
                ? FuzzOutcome.broken("what a read leaves behind is bounded by its input",
                        rendered + " characters came out of " + input.length + " bytes")
                : FuzzOutcome.held();
    }

    /**
     * How much text one value holds: every member name and every string inside it.
     *
     * <p>The measure is the input's own content rather than the value's printed form, so what this
     * bounds is what the read allocated on the caller's behalf.</p>
     *
     * @param value what was read
     * @return the character count
     */
    private static long textIn(DocumentValue value) {
        return switch (value) {
            case final DocumentValue.Mapping mapping -> mapping.members().entrySet().stream()
                    .mapToLong(member -> member.getKey().length() + textIn(member.getValue()))
                    .sum();
            case final DocumentValue.Sequence sequence -> sequence.items().stream()
                    .mapToLong(DocumentReaderTarget::textIn)
                    .sum();
            case final DocumentValue.Text text -> text.value().length();
            case DocumentValue.Whole whole -> String.valueOf(whole.value()).length();
            case DocumentValue.Flag flag -> String.valueOf(flag.value()).length();
            case DocumentValue.Nothing ignored -> 0;
        };
    }
}
