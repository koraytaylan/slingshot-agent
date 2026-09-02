// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether the continuation key ring can actually issue a token that validates.
 *
 * <p>Performed rather than inspected, because the case that matters is an authority that is present
 * and unusable: a ring whose current key is there, whose record reads, and whose token nothing
 * accepts. Inspecting the ring answers that everything is where it should be, which is exactly what
 * an operator was already able to see.</p>
 *
 * <p>Performing it is a write and a read against a shared record, so the answer is held for the
 * interval the contract declares and the sentence says when it was last performed. A dashboard and
 * a monitor both polling is the ordinary case rather than the exception, and a check that repeated
 * real work for every poll would be a load somebody installed on purpose. A held answer is never
 * mistaken for a fresh one, because the instant it carries is the instant it was true.</p>
 */
public final class ContinuationAuthorityHealthCheck {

    /** What performing one issue-and-validate found. */
    public sealed interface Attempt permits Validated, NotValidated {
    }

    /**
     * A token this authority issued and then accepted back.
     *
     * @param keyIdentifier which key signed it
     */
    public record Validated(String keyIdentifier) implements Attempt {
    }

    /**
     * One it did not.
     *
     * @param detail what went wrong, at the step it went wrong at
     */
    public record NotValidated(String detail) implements Attempt {
    }

    /** What performs the real work: issues a token from the ring, then validates it back. */
    @FunctionalInterface
    public interface IssueAndValidate {

        /**
         * Does it, once.
         *
         * @return what happened
         */
        Attempt perform();
    }

    /** What is remembered between polls: nothing yet, or one answer and when it was true. */
    private sealed interface Memory permits NothingYet, Ran {
    }

    private record NothingYet() implements Memory {
    }

    private record Ran(AgentHealth.Result result, long atUnixMilliseconds) implements Memory {
    }

    private final IssueAndValidate work;
    private final long intervalMilliseconds;
    private final AtomicReference<Memory> remembered = new AtomicReference<>(new NothingYet());

    /**
     * Holds one check over the work it performs.
     *
     * @param work what issues a token and validates it back
     * @param intervalMilliseconds how long one answer is held before the work is done again, which
     *     the contract declares
     */
    public ContinuationAuthorityHealthCheck(IssueAndValidate work, long intervalMilliseconds) {
        this.work = work;
        this.intervalMilliseconds = intervalMilliseconds;
    }

    /**
     * What this authority could do, as of now — performing the work only where the held answer has
     * aged past the declared interval.
     *
     * @param nowUnixMilliseconds when this poll arrived
     * @return one result an operator can act on, saying when it was last performed
     */
    public AgentHealth.Result at(long nowUnixMilliseconds) {
        final Memory held = remembered.get();
        if (held instanceof final Ran ran
                && nowUnixMilliseconds - ran.atUnixMilliseconds() < intervalMilliseconds
                && nowUnixMilliseconds >= ran.atUnixMilliseconds()) {
            return ran.result();
        }
        final AgentHealth.Result fresh = resultOf(work.perform(), nowUnixMilliseconds);
        remembered.set(new Ran(fresh, nowUnixMilliseconds));
        return fresh;
    }

    /**
     * What one performance reads as.
     *
     * @param attempt what the work found
     * @param atUnixMilliseconds when it was performed
     * @return the result, carrying the instant it was true
     */
    private static AgentHealth.Result resultOf(Attempt attempt, long atUnixMilliseconds) {
        return switch (attempt) {
            case final Validated validated -> AgentHealth.healthy(
                    AgentHealth.Check.CONTINUATION_AUTHORITY, "the key ring issued a continuation"
                            + " token under " + validated.keyIdentifier() + " and accepted it"
                            + " back, performed at " + atUnixMilliseconds);
            case final NotValidated refused -> AgentHealth.unhealthy(
                    AgentHealth.Check.CONTINUATION_AUTHORITY, "the key ring could not issue a"
                            + " continuation token and accept it back: " + refused.detail()
                            + ". Every paged read a caller resumes is signed by this ring, so what"
                            + " fails is the second page rather than the first. Performed at "
                            + atUnixMilliseconds + ".");
        };
    }
}
