// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Whether something the platform is running is held or moving.
 *
 * <p>One type for a workflow instance and a job queue alike, because it is one question and the two
 * commands that ask it would otherwise each grow their own answer. Named rather than two-valued:
 * {@code suspend(true)} at a call site says nothing at all, and the reader has to go and find out
 * which way round it is at exactly the moment they are deciding whether to stop somebody's
 * production queue.</p>
 *
 * <p>The same type is what a caller asks for and what the platform reports afterwards, which is
 * what lets a result be compared against a request with no conversion in between. A conversion is
 * where the two quietly stop meaning the same thing.</p>
 */
public enum SuspensionState {

    /** Held: it will not take new work until it is resumed. */
    SUSPENDED("suspended"),

    /** Moving: it is taking work. */
    RUNNING("running");

    private final String spelling;

    SuspensionState(String spelling) {
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
     * The state one argument names.
     *
     * @param spelled what the caller sent
     * @return the state, or nothing where that is not one of the two
     */
    public static Optional<SuspensionState> of(DocumentValue spelled) {
        return spelled instanceof final DocumentValue.Text text ? named(text.value())
                : Optional.empty();
    }

    /**
     * The state one spelling names.
     *
     * @param spelled what was written
     * @return the state, or nothing where nothing is spelled that way
     */
    public static Optional<SuspensionState> named(String spelled) {
        return Arrays.stream(values())
                .filter(state -> state.spelling.equals(spelled))
                .findFirst();
    }

    /**
     * Both states, spelled as the wire spells them.
     *
     * @return the spellings, in declaration order
     */
    public static List<String> spellings() {
        return Arrays.stream(values()).map(SuspensionState::spelling).toList();
    }
}
