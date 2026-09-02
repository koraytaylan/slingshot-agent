// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Arrays;
import java.util.Optional;

/**
 * Where a command runs, which decides whose identity it runs under.
 *
 * <p>{@code IMMEDIATE} means inside the request that submitted it, on that request's own session.
 * That is what makes running as the caller free rather than granted: there is no impersonation
 * anywhere in this repository, and nothing here can obtain a session for somebody who is not making
 * the request.</p>
 *
 * <p>{@code DEFERRED} means later, in a job — which requires an answer to the question of whose
 * identity it runs under. This build has no such answer, so a deferred row is refused until
 * somebody declares one. Every row this product ships is immediate; the class exists so that adding
 * the first command that cannot finish inside a request is a decision somebody makes deliberately
 * rather than a property they inherit.</p>
 */
public enum ExecutionClass {

    /** Inside the request that submitted it, on that request's own session. */
    IMMEDIATE("immediate"),

    /** Later, in a job, under an identity nobody has declared. */
    DEFERRED("deferred");

    private final String spelling;

    ExecutionClass(String spelling) {
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
    public static Optional<ExecutionClass> named(String spelling) {
        return Arrays.stream(values())
                .filter(held -> held.spelling.equals(spelling))
                .findFirst();
    }
}
