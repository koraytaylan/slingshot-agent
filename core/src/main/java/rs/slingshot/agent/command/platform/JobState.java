// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What state a Sling job is in.
 *
 * <p>Six values, and the three an operator cares about are the ones that mean work did not happen:
 * {@code error} is a job that failed and will be retried, {@code cancelled} is one that failed
 * often enough that the platform stopped, and {@code dropped} is one the platform discarded without
 * running. They are told apart because the answers differ — the first waits, the second needs
 * somebody, and the third means a queue was configured to throw work away.</p>
 */
public enum JobState {

    /** It is running now. */
    ACTIVE("active"),

    /** It is waiting to run. */
    QUEUED("queued"),

    /** It ran and finished. */
    SUCCEEDED("succeeded"),

    /** It failed and will be tried again. */
    ERROR("error"),

    /** It failed often enough that the platform stopped trying. */
    CANCELLED("cancelled"),

    /** The platform discarded it without running it. */
    DROPPED("dropped");

    private final String spelling;

    JobState(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How the wire spells this state.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The state one spelling names.
     *
     * @param spelled what was written
     * @return the state, or nothing where nothing is spelled that way
     */
    public static Optional<JobState> named(String spelled) {
        return Arrays.stream(values())
                .filter(state -> state.spelling.equals(spelled))
                .findFirst();
    }

    /**
     * Every state, spelled as the wire spells it.
     *
     * @return the spellings, in declaration order
     */
    public static List<String> spellings() {
        return Arrays.stream(values()).map(JobState::spelling).toList();
    }
}
