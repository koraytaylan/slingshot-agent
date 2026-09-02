// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

/**
 * Which incarnation of the agent's event store an operation belongs to.
 *
 * <p>It starts at one and never decreases. A store that was rebuilt gets a later generation, and a
 * document presenting an earlier one is presenting itself as belonging to a store that no longer
 * exists — which is exactly how a replayed submission from before a rebuild would be mistaken for a
 * live one. Zero is refused because there is no generation before the first.</p>
 */
public final class EventStoreGeneration implements Comparable<EventStoreGeneration> {

    /** The generation a store begins at, which nothing is before. */
    public static final long FIRST = 1;

    private final long number;

    private EventStoreGeneration(long number) {
        this.number = number;
    }

    /** Why a number is not a generation, or not this one. */
    public enum Refusal {
        /** It is below the first generation, and there is nothing before the first. */
        BEFORE_THE_FIRST,
        /** It is below a generation already observed, so the store went backwards. */
        BEFORE_ONE_ALREADY_SEEN
    }

    /** The result of holding one: the generation, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A number that is a generation.
     *
     * @param generation the generation it is
     */
    public record Held(EventStoreGeneration generation) implements Outcome {
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
     * Holds a generation number.
     *
     * @param number the number
     * @return the generation, or the one reason there is none
     */
    public static Outcome of(long number) {
        if (number < FIRST) {
            return new Refused(Refusal.BEFORE_THE_FIRST,
                    number + " is before the first generation, which is " + FIRST);
        }
        return new Held(new EventStoreGeneration(number));
    }

    /**
     * This generation, if it may follow one already observed.
     *
     * @param observed the latest generation already observed
     * @return this generation, or the one reason a store cannot have gone back to it
     */
    public Outcome notBefore(EventStoreGeneration observed) {
        if (number < observed.number) {
            return new Refused(Refusal.BEFORE_ONE_ALREADY_SEEN, number
                    + " is before " + observed.number + ", which this store has already been at");
        }
        return new Held(this);
    }

    /**
     * The generation's own number.
     *
     * @return the number
     */
    public long number() {
        return number;
    }

    @Override
    public int compareTo(EventStoreGeneration other) {
        return Long.compare(number, other.number);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final EventStoreGeneration generation && number == generation.number;
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
