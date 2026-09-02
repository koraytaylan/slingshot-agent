// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Arrays;
import java.util.Optional;

/**
 * Whether a command changes anything, which the two halves of this product agree on.
 *
 * <p>The client declares the same two values for the same commands, and the conformance gate
 * compares them. What this side adds is enforcement: a read runs on a session that refuses to
 * commit, so "this command replaces nothing" is a property of the machinery rather than a claim in
 * a table.</p>
 */
public enum AccessClass {

    /** It changes no repository or replicated content. */
    READ("read"),

    /** It can change authored or replicated content. */
    WRITE("write");

    private final String spelling;

    AccessClass(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this class is spelled where it is written down.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The class one spelling names.
     *
     * @param spelling the spelling
     * @return the class, or nothing where this build knows none spelled that way
     */
    public static Optional<AccessClass> named(String spelling) {
        return Arrays.stream(values())
                .filter(held -> held.spelling.equals(spelling))
                .findFirst();
    }
}
