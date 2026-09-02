// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

/**
 * Where one event sits in the run of events about one operation.
 *
 * <p>It starts at zero and strictly increases within one operation and one generation. A repeat and
 * a decrease are two different failures: a repeat is a delivery a job system made twice, which is a
 * thing that happens and is handled, and a decrease is a store or a sender that has gone backwards,
 * which is not.</p>
 */
public final class EventSequence implements Comparable<EventSequence> {

    /** The sequence the first event about an operation carries. */
    public static final long FIRST = 0;

    private final long number;

    private EventSequence(long number) {
        this.number = number;
    }

    /** Why a number is not a sequence, or not the next one. */
    public enum Refusal {
        /** It is below the first sequence, and there is nothing before the first. */
        BEFORE_THE_FIRST,
        /** It is the sequence already seen, which is one event delivered twice. */
        REPEATED,
        /** It is below a sequence already seen, which is a run of events going backwards. */
        WENT_BACKWARDS
    }

    /** The result of holding one: the sequence, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A number that is a sequence, or the one that may follow.
     *
     * @param sequence the sequence it is
     */
    public record Held(EventSequence sequence) implements Outcome {
    }

    /**
     * A number that is not one, or not one that may follow what has been seen.
     *
     * @param refusal why it is not
     * @param detail what was observed, naming both numbers where two were compared
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Holds a sequence number.
     *
     * @param number the number
     * @return the sequence, or the one reason there is none
     */
    public static Outcome of(long number) {
        if (number < FIRST) {
            return new Refused(Refusal.BEFORE_THE_FIRST,
                    number + " is before the first sequence, which is " + FIRST);
        }
        return new Held(new EventSequence(number));
    }

    /**
     * This sequence, if it may follow one already seen.
     *
     * @param seen the last sequence already seen for this operation and generation
     * @return this sequence, or the one reason it cannot follow that one
     */
    public Outcome after(EventSequence seen) {
        if (number == seen.number) {
            return new Refused(Refusal.REPEATED,
                    number + " has already been seen, so this event arrived twice");
        }
        if (number < seen.number) {
            return new Refused(Refusal.WENT_BACKWARDS,
                    number + " is before " + seen.number + ", which has already been seen");
        }
        return new Held(this);
    }

    /**
     * The sequence's own number.
     *
     * @return the number
     */
    public long number() {
        return number;
    }

    @Override
    public int compareTo(EventSequence other) {
        return Long.compare(number, other.number);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final EventSequence sequence && number == sequence.number;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(number);
    }

    @Override
    public String toString() {
        return Long.toString(number);
    }
}
