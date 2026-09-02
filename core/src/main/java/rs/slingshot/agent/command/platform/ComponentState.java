// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What state a declarative service component is in.
 *
 * <p>The one worth knowing is {@code unsatisfied}: the component is enabled, its bundle is running,
 * and something it requires is not there. That is the state behind most of the "the bundle is
 * active but the feature does not work" reports anybody ever files, and it is invisible from the
 * bundle listing — which is exactly why this command exists beside that one.</p>
 */
public enum ComponentState {

    /** It is running. */
    ACTIVE("active"),

    /** Somebody turned it off. */
    DISABLED("disabled"),

    /** Everything it requires is there. */
    SATISFIED("satisfied"),

    /** Something it requires is not there, which is the state most reports are really about. */
    UNSATISFIED("unsatisfied");

    private final String spelling;

    ComponentState(String spelling) {
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
    public static Optional<ComponentState> named(String spelled) {
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
        return Arrays.stream(values()).map(ComponentState::spelling).toList();
    }
}
