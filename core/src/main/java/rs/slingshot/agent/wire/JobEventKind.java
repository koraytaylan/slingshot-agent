// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The five things this agent says happened to a job, and nothing else.
 *
 * <p>The set is closed because a client meeting a kind it did not know would have to guess whether
 * it mattered, and both answers are wrong: an unknown terminal kind read as progress waits forever,
 * and an unknown progress kind read as terminal reports an outcome that has not happened.</p>
 *
 * <p>Terminality is a property of the kind rather than a member beside it, so nothing can describe
 * a kind as terminal in one document and not in another.</p>
 */
public enum JobEventKind {

    /** The submission was taken and a job exists for it. */
    ACCEPTED("accepted", Finality.CONTINUES),

    /** A worker began running it. */
    STARTED("started", Finality.CONTINUES),

    /** It is still running and something about it changed. */
    PROGRESS("progress", Finality.CONTINUES),

    /** It finished and did what it was asked. */
    SUCCEEDED("succeeded", Finality.ENDS),

    /** It finished and did not. */
    FAILED("failed", Finality.ENDS);

    /** Whether a kind is the last thing that will be said about a job. */
    public enum Finality {
        /** Something else will be said about this job. */
        CONTINUES,
        /** Nothing else will be. */
        ENDS
    }

    private final String spelling;
    private final Finality finality;

    JobEventKind(String spelling, Finality finality) {
        this.spelling = spelling;
        this.finality = finality;
    }

    /**
     * How this kind is spelled on the wire.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * Whether anything else will be said about a job after this.
     *
     * @return the finality
     */
    public Finality finality() {
        return finality;
    }

    /**
     * The kind one spelling names.
     *
     * @param spelling the spelling
     * @return the kind, or nothing where this build knows no such kind
     */
    public static Optional<JobEventKind> named(String spelling) {
        return Arrays.stream(values())
                .filter(kind -> kind.spelling.equals(spelling))
                .findFirst();
    }

    /**
     * Every spelling this build knows.
     *
     * @return the spellings, sorted
     */
    public static List<String> spellings() {
        return Arrays.stream(values())
                .map(JobEventKind::spelling)
                .sorted()
                .toList();
    }
}
