// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.identity;

/**
 * Which behavioural revision this build was made with.
 *
 * <p>Format and contract digests answer whether two sides can speak at all. They do not answer
 * whether the other side behaves the way this one expects, because a behavioural fix that changes
 * no contract leaves every digest identical: an agent deployed before such a fix describes the same
 * format and holds the same contracts while answering differently, and a client that could not tell
 * the two apart would read the older build's refusal as an outcome nobody can interpret.</p>
 *
 * <p>So the number is bumped whenever this agent's behaviour changes without a contract change, and
 * the client compares it exactly. It is deliberately not the bundle version: a bundle version is
 * whatever a release pipeline assigned, while this is a statement about what the code does.</p>
 */
public final class CapabilityRevision {

    /** The revision this build declares, which is bumped with every behavioural change. */
    public static final long CURRENT = 2;

    /** The first revision, which nothing is before. */
    public static final long FIRST = 1;

    private final long number;

    private CapabilityRevision(long number) {
        this.number = number;
    }

    /** Why a number is not a revision. */
    public enum Refusal {
        /** It is below the first revision, and there is nothing before the first. */
        BEFORE_THE_FIRST
    }

    /** The result of holding one: the revision, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A number that is a revision.
     *
     * @param revision the revision it is
     */
    public record Held(CapabilityRevision revision) implements Outcome {
    }

    /**
     * A number that is not one.
     *
     * @param refusal why it is not
     * @param detail what was observed
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Holds a revision number.
     *
     * @param number the number
     * @return the revision, or the one reason there is none
     */
    public static Outcome of(long number) {
        if (number < FIRST) {
            return new Refused(Refusal.BEFORE_THE_FIRST,
                    number + " is before the first revision, which is " + FIRST);
        }
        return new Held(new CapabilityRevision(number));
    }

    /**
     * The revision's own number.
     *
     * @return the number
     */
    public long number() {
        return number;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof final CapabilityRevision revision && number == revision.number;
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
