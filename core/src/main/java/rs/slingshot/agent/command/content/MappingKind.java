// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Arrays;
import java.util.Optional;

/**
 * Which kind of rule one mapping entry is.
 *
 * <p>The platform keeps four sorts of thing in one table and they do different things to a request.
 * An operator chasing an address that came out wrong needs to know which sort they are looking at:
 * a rule that rewrites a path on the way in and a rule that sends the browser somewhere else are
 * indistinguishable from their pattern alone and produce completely different symptoms.</p>
 */
public enum MappingKind {

    /** A short name standing in for a resource, resolved on the way in. */
    ALIAS("alias"),

    /** A rewrite applied on the way in, which the caller never sees. */
    INTERNAL_REDIRECT("internal_redirect"),

    /** A rewrite applied on the way out, which is how addresses are published. */
    MAP("map"),

    /** A rule that answers with a redirect, which the caller does see. */
    REDIRECT("redirect");

    private final String spelling;

    MappingKind(String spelling) {
        this.spelling = spelling;
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
     * The kind one spelling names.
     *
     * @param spelling the spelling
     * @return the kind, or nothing where no kind is spelled that way
     */
    public static Optional<MappingKind> named(String spelling) {
        return Arrays.stream(values())
                .filter(kind -> kind.spelling.equals(spelling))
                .findFirst();
    }
}
