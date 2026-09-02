// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import rs.slingshot.agent.json.DocumentValue;

/**
 * Whether a translation says which rules it went through, or only where it arrived.
 *
 * <p>A caller who wants the answer and a caller who is debugging the mapping table want two
 * different things, and the second is much larger. So it is asked for rather than always sent: a
 * trace nobody wanted is a bounded list of paths on every single request.</p>
 *
 * <p>Named rather than carried as a bare boolean inside this build, though it crosses the wire as
 * one because the client's schema declares it so. A parameter called {@code trace} that is either
 * true or false reads identically whichever it is at the call site; these two do not.</p>
 */
public enum TraceDisclosure {

    /** The rules the translation went through travel back with the answer. */
    INCLUDED,

    /** Only where the translation arrived travels back. */
    OMITTED;

    /** The member a caller asks for a trace in. */
    public static final String ARGUMENT_MEMBER = "include_trace";

    /**
     * What one caller asked for, read from the flag they sent.
     *
     * @param asked the value the caller sent
     * @return what they asked for, or nothing where they sent something that is not a flag
     */
    public static java.util.Optional<TraceDisclosure> of(DocumentValue asked) {
        if (!(asked instanceof final DocumentValue.Flag flag)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(flag.value() == DocumentValue.Truth.TRUE ? INCLUDED : OMITTED);
    }
}
