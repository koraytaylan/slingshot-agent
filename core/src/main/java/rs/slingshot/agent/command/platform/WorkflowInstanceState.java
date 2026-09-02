// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What state a workflow instance is actually in, which is a wider question than what to ask for.
 *
 * <p>A caller may ask for two of these — running or suspended — and the platform may report any of
 * five. That asymmetry is real and it is the reason this is a separate type from
 * {@link SuspensionState}: an instance that finished, was aborted, or went stale while the request
 * was in flight is none of the two things anybody can ask for, and a single type covering both
 * would either invent requests nobody can make or lose answers the platform actually gives.</p>
 *
 * <p>Which means a result cannot be compared against a request for equality. It can be compared for
 * <em>agreement</em> — asking for suspended and observing suspended is agreement; asking for
 * suspended and observing completed is not a failure but it is not what was asked for either, and
 * the caller has to be told which.</p>
 */
public enum WorkflowInstanceState {

    /** It is taking work. */
    RUNNING("running"),

    /** It is held and will not take work until it is resumed. */
    SUSPENDED("suspended"),

    /** It finished. */
    COMPLETED("completed"),

    /** It was ended before it finished. */
    ABORTED("aborted"),

    /** The platform no longer holds enough about it to say, which is its own answer. */
    STALE("stale");

    private final String spelling;

    WorkflowInstanceState(String spelling) {
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
     * Whether observing this state means the request was carried out.
     *
     * @param requested what the caller asked for
     * @return whether the observation agrees with the request
     */
    public boolean agreesWith(SuspensionState requested) {
        return spelling.equals(requested.spelling());
    }

    /**
     * The state one spelling names.
     *
     * @param spelled what was written
     * @return the state, or nothing where nothing is spelled that way
     */
    public static Optional<WorkflowInstanceState> named(String spelled) {
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
        return Arrays.stream(values()).map(WorkflowInstanceState::spelling).toList();
    }
}
