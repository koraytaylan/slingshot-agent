// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Arrays;
import java.util.Optional;

/**
 * Whether a caller naming several things wants any of them or all of them.
 *
 * <p>Required rather than defaulted, because the two answers differ by more than degree: a page
 * using one of five components and a page using all five are different populations, and a migration
 * planned against the wrong one is planned against a set somebody else's answer produced. Neither
 * is the obvious default — looking for any of a set is the commoner question and looking for all of
 * it is the more consequential one — so the caller says which.</p>
 */
public enum MatchMode {

    /** One of the named things is enough. */
    ANY("any"),

    /** Every named thing has to be there. */
    ALL("all");

    private final String spelling;

    MatchMode(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this mode is spelled on the wire.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The mode one spelling names.
     *
     * @param spelling the spelling as it arrived
     * @return the mode, or nothing where no mode is spelled that way
     */
    public static Optional<MatchMode> named(String spelling) {
        return Arrays.stream(values())
                .filter(mode -> mode.spelling.equals(spelling))
                .findFirst();
    }
}
