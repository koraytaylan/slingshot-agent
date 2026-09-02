// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What a destructive command does about the things that point at what it is removing.
 *
 * <p>Required on every one of them, with no default. Deleting a page other pages link to is
 * sometimes exactly right — a decommissioned section whose links are going too — and sometimes
 * catastrophic, and nothing on this side can tell which. A default would make the catastrophic case
 * the one somebody gets without having chosen it.</p>
 *
 * <p>Two values and no third. "Refuse and tell me what stopped you" and "go ahead" are the whole of
 * what a caller can mean; anything between them — remove some, warn and continue — is a partial
 * outcome, and a partial deletion is the state nobody can reason about afterwards.</p>
 */
public enum ReferencePolicy {

    /** Remove it whatever points at it. */
    IGNORE_REFERENCES("ignore_references"),

    /** Refuse where anything points at it, and say what did. */
    REFUSE_WHEN_REFERENCED("refuse_when_referenced");

    /** The member a caller states this in. */
    public static final String ARGUMENT_MEMBER = "reference_policy";

    private final String spelling;

    ReferencePolicy(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How this policy is spelled on the wire.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The policy one spelling names.
     *
     * @param spelling the spelling as it arrived
     * @return the policy, or nothing where no policy is spelled that way
     */
    public static Optional<ReferencePolicy> named(String spelling) {
        return Arrays.stream(values())
                .filter(policy -> policy.spelling.equals(spelling))
                .findFirst();
    }

    /**
     * Every policy's spelling, so a refusal can name what a caller may choose between.
     *
     * @return the spellings, in the order they are declared
     */
    public static List<String> spellings() {
        return Arrays.stream(values())
                .map(ReferencePolicy::spelling)
                .toList();
    }
}
