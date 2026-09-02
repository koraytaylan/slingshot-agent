// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.Optional;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.RegistryRow;

/**
 * How many commits a command owes, and the wrapper that holds it to that.
 *
 * <p>How many is read from the command's own registry row rather than assumed, because not
 * everything that changes something changes a repository. A row declaring
 * {@link #OUTCOME_UNKNOWN} changes the caller's repository and owes exactly one; a
 * row declaring an admission or a platform control changes something that is not the caller's
 * repository and owes none. Offering content to replication is not a mutation that happens to
 * commit nothing, and a wrapper that demanded a commit from it would be demanding a write nobody
 * asked for.</p>
 *
 * <p>Reading it from the row rather than from the handler's package or its name is the point: the
 * day somebody writes a command in the mutation package that is really an admission, the row is
 * what says so.</p>
 */
public final class SingleCommit {

    private SingleCommit() {
    }

    /** The category a repository mutation whose outcome nobody knows is reported under. */
    public static final String OUTCOME_UNKNOWN = "mutation_outcome_unknown";

    /** The category an admission whose outcome nobody knows is reported under. */
    public static final String ADMISSION_OUTCOME_UNKNOWN = "admission_outcome_unknown";

    /** The category a platform control whose outcome nobody knows is reported under. */
    public static final String PLATFORM_CONTROL_OUTCOME_UNKNOWN =
            "platform_control_outcome_unknown";

    /** How many commits a command owes on a path that succeeded. */
    public enum Expectation {
        /** Exactly one, because it changes the caller's own repository. */
        ONE_COMMIT,
        /** None, because what it changes is not the caller's repository. */
        NO_COMMIT
    }

    /** How a command failed to owe what it owes. */
    public enum Breach {
        /** It committed twice, so a process stopping between them leaves a described state. */
        COMMITTED_TWICE,
        /** It reported a change and committed nothing, so the change is not in the repository. */
        CHANGED_WITHOUT_COMMITTING,
        /** It refused and committed anyway, so a failure left something behind. */
        COMMITTED_WHILE_REFUSING,
        /** It committed, and what it changes is not the caller's repository. */
        COMMITTED_WITHOUT_OWING
    }

    /** What running one mutation produced: its answer, or the rule it broke. */
    public sealed interface Outcome permits Ran, Refused {
    }

    /**
     * A command that owed what it owes.
     *
     * @param outcome what it answered
     */
    public record Ran(MutationOutcome outcome) implements Outcome {
    }

    /**
     * One that did not.
     *
     * @param breach which rule it broke
     * @param commits how many commits it made
     */
    public record Refused(Breach breach, long commits) implements Outcome {
    }

    /** What one mutation does, given a session whose commits are counted. */
    @FunctionalInterface
    public interface Mutation {

        /**
         * Runs it.
         *
         * @param session the caller's own session, counting commits
         * @return what happened
         */
        MutationOutcome apply(ResourceResolver session);
    }

    /**
     * How many commits one row's command owes, where its categories say.
     *
     * @param row the command's registry row
     * @return the expectation, or nothing where the row declares no unknown outcome at all and is
     *     therefore not a command this wrapper is about
     */
    public static Optional<Expectation> expectationOf(RegistryRow row) {
        if (row.failureCategories().contains(OUTCOME_UNKNOWN)) {
            return Optional.of(Expectation.ONE_COMMIT);
        }
        if (row.failureCategories().contains(ADMISSION_OUTCOME_UNKNOWN)
                || row.failureCategories()
                        .contains(PLATFORM_CONTROL_OUTCOME_UNKNOWN)) {
            return Optional.of(Expectation.NO_COMMIT);
        }
        return Optional.empty();
    }

    /**
     * Runs one mutation and holds it to what its row says it owes.
     *
     * @param expectation how many commits it owes
     * @param caller the requesting user's own resolver
     * @param mutation what to run
     * @return what it answered, or the rule it broke
     */
    public static Outcome around(Expectation expectation, ResourceResolver caller,
                                 Mutation mutation) {
        final CountingResolver counting = CountingResolver.around(caller);
        final MutationOutcome outcome = mutation.apply(counting);
        return breached(expectation, outcome, counting.commits())
                .<Outcome>map(breach -> new Refused(breach, counting.commits()))
                .orElseGet(() -> new Ran(outcome));
    }

    /**
     * Which rule one run broke, where it broke one.
     *
     * <p>An unknown outcome is held to nothing, which is what makes it honest: the whole meaning of
     * it is that this side cannot say whether the commit landed, so a count of nought and a count of
     * one are both consistent with it. Every other answer is held exactly.</p>
     *
     * @param expectation how many commits the command owes
     * @param outcome what it answered
     * @param commits how many commits it made
     * @return the rule it broke, or nothing where it broke none
     */
    private static Optional<Breach> breached(Expectation expectation, MutationOutcome outcome,
                                             long commits) {
        if (commits > 1) {
            return Optional.of(Breach.COMMITTED_TWICE);
        }
        if (outcome instanceof MutationOutcome.Unknown) {
            return Optional.empty();
        }
        if (expectation == Expectation.NO_COMMIT) {
            return commits > 0 ? Optional.of(Breach.COMMITTED_WITHOUT_OWING) : Optional.empty();
        }
        if (outcome instanceof MutationOutcome.Changed && commits == 0) {
            return Optional.of(Breach.CHANGED_WITHOUT_COMMITTING);
        }
        return outcome instanceof MutationOutcome.Refused && commits > 0
                ? Optional.of(Breach.COMMITTED_WHILE_REFUSING) : Optional.empty();
    }
}
