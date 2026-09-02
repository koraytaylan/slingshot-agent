// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What state a bundle is in, which is the framework's own vocabulary rather than this build's.
 *
 * <p>Six values, and the two an operator most often confuses are the two that matter most:
 * {@code installed} means the framework has the bundle and cannot satisfy what it needs, and
 * {@code resolved} means it can and has not started it. A bundle sitting in the first is usually a
 * missing dependency somebody has to go and supply; one sitting in the second is usually a
 * component that failed to activate. Reporting either as "not running" would send half of them to
 * the wrong place.</p>
 */
public enum BundleState {

    /** It is running. */
    ACTIVE("active"),

    /** The framework has it and cannot satisfy what it needs. */
    INSTALLED("installed"),

    /** The framework can satisfy it and has not started it. */
    RESOLVED("resolved"),

    /** It is starting. */
    STARTING("starting"),

    /** It is stopping. */
    STOPPING("stopping"),

    /** It is gone. */
    UNINSTALLED("uninstalled");

    private final String spelling;

    BundleState(String spelling) {
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
    public static Optional<BundleState> named(String spelled) {
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
        return Arrays.stream(values()).map(BundleState::spelling).toList();
    }
}
