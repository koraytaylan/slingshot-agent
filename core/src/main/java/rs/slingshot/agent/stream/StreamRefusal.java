// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.stream;

import java.util.Arrays;
import java.util.Optional;

/**
 * The four ways a stream ends rather than carries something.
 *
 * <p>Every one of them ends the stream instead of shortening an event. A truncated event is not a
 * smaller event, it is an unparseable one, and the subscriber receiving it has no way to tell which
 * of the two it got — so a bound is a reason to stop rather than a reason to cut.</p>
 */
public enum StreamRefusal {

    /** One line is longer than a line may be. */
    LINE_TOO_LONG("line_too_long"),

    /** One event's fields come to more than an event may. */
    EVENT_TOO_LARGE("event_too_large"),

    /** One cursor is longer than a cursor may be. */
    IDENTIFIER_TOO_LONG("identifier_too_long"),

    /** This stream is already holding as much as it may for a reader that is not reading. */
    BUFFER_FULL("buffer_full");

    private final String spelling;

    StreamRefusal(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this refusal is spelled where it is written down.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The refusal one spelling names.
     *
     * @param spelling the spelling
     * @return the refusal, or nothing where this build has no such refusal
     */
    public static Optional<StreamRefusal> named(String spelling) {
        return Arrays.stream(values())
                .filter(refusal -> refusal.spelling.equals(spelling))
                .findFirst();
    }
}
