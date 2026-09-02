// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import java.util.Optional;
import rs.slingshot.agent.identity.EventStoreGeneration;
import rs.slingshot.agent.wire.EventSequence;

/**
 * Where a subscriber got to, which is a sequence and the incarnation it is a sequence of.
 *
 * <p>The generation travels with the sequence because without it a cursor from a store that has
 * been rotated reads as an early position in the store that replaced it — and an early position is
 * served, quietly, as though the subscriber were merely behind. It is not behind; the events it is
 * asking about no longer exist, and being told so is the only answer that lets it resynchronise.</p>
 *
 * @param generation the incarnation the sequence belongs to
 * @param sequence the newest event the subscriber has been shown
 */
public record ReplayCursor(EventStoreGeneration generation, EventSequence sequence) {

    /** What separates the two halves where a cursor is written as one string. */
    public static final String SEPARATOR = ":";

    /** How many parts a written cursor has. */
    private static final int PARTS = 2;

    /** Why a written cursor is not one this build reads. */
    public enum Refusal {
        /** It is not a generation and a sequence separated by the one separator. */
        NOT_TWO_PARTS,
        /** Its generation half is not one this build reads. */
        GENERATION_REFUSED,
        /** Its sequence half is not one this build reads. */
        SEQUENCE_REFUSED
    }

    /** The result of reading a cursor: one this build understands, or why it does not. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A cursor this build understands.
     *
     * @param cursor the cursor
     */
    public record Held(ReplayCursor cursor) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * A cursor from the two numbers it is made of.
     *
     * @param generation which incarnation
     * @param sequence which sequence in it
     * @return the cursor, or the one reason there is none
     */
    public static Outcome of(long generation, long sequence) {
        final EventStoreGeneration.Outcome held = EventStoreGeneration.of(generation);
        if (held instanceof final EventStoreGeneration.Refused refused) {
            return new Refused(Refusal.GENERATION_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        final EventSequence.Outcome counted = EventSequence.of(sequence);
        if (counted instanceof final EventSequence.Refused refused) {
            return new Refused(Refusal.SEQUENCE_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        return new Held(new ReplayCursor(((EventStoreGeneration.Held) held).generation(),
                ((EventSequence.Held) counted).sequence()));
    }

    /**
     * A cursor from the way one is written where it travels.
     *
     * @param rendered the cursor as it was written
     * @return the cursor, or the one reason there is none
     */
    public static Outcome read(String rendered) {
        final String[] parts = rendered.split(SEPARATOR, -1);
        if (parts.length != PARTS) {
            return new Refused(Refusal.NOT_TWO_PARTS, "a cursor is a generation and a sequence"
                    + " separated by '" + SEPARATOR + "', and this is " + rendered);
        }
        return whole(parts[0])
                .flatMap(generation -> whole(parts[1]).map(sequence -> of(generation, sequence)))
                .orElseGet(() -> new Refused(Refusal.NOT_TWO_PARTS,
                        "both halves of a cursor are whole numbers, and " + rendered + " is not"));
    }

    private static Optional<Long> whole(String part) {
        return part.chars().allMatch(scalar -> scalar >= '0' && scalar <= '9') && !part.isEmpty()
                && part.length() < Long.toString(Long.MAX_VALUE).length()
                ? Optional.of(Long.parseLong(part))
                : Optional.empty();
    }

    /**
     * How this cursor is written where it travels.
     *
     * @return the cursor as one string
     */
    public String rendered() {
        return generation.number() + SEPARATOR + sequence.number();
    }

    /**
     * The one reason a cursor was refused, where it was.
     *
     * @param outcome what reading it produced
     * @return the refusal, or nothing where there is a cursor
     */
    public static Optional<Refused> refusalIn(Outcome outcome) {
        return outcome instanceof final Refused refused ? Optional.of(refused) : Optional.empty();
    }
}
