// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.property;

import java.util.Arrays;
import java.util.Optional;

/**
 * What kind of thing one value a search compares against is.
 *
 * <p>Carried with every value rather than inferred from how it was written, so {@code "1"} the
 * string and {@code 1} the whole number are two different questions instead of one question whose
 * meaning depends on the caller's spelling. Unlike kinds never compare at all, which makes a
 * mistyped predicate a refusal rather than a result that is silently empty — and a silently empty
 * result is the failure mode that costs somebody a day.</p>
 */
public enum ScalarKind {

    /** Text. */
    STRING("string", Ordering.ORDERED),

    /** True or false. */
    BOOLEAN("boolean", Ordering.ORDERED),

    /** A whole number over the repository's full signed range. */
    INTEGER("integer", Ordering.ORDERED),

    /** A decimal, keeping the scale it was written with. */
    DECIMAL("decimal", Ordering.ORDERED),

    /** An instant, in the contract's canonical spelling. */
    DATE_TIME("date_time", Ordering.ORDERED),

    /**
     * An address.
     *
     * <p>The one kind with no order. Asking whether one address sorts before another is not a
     * question with an answer, so an ordered comparison naming a path is refused when it is read
     * rather than answered arbitrarily.</p>
     */
    REPOSITORY_PATH("repository_path", Ordering.UNORDERED);

    /** Whether a kind's values can be put in order. */
    public enum Ordering {
        /** They can, so an ordered comparison against one means something. */
        ORDERED,
        /** They cannot, and an ordered comparison against one is refused. */
        UNORDERED
    }

    private final String spelling;
    private final Ordering ordering;

    ScalarKind(String spelling, Ordering ordering) {
        this.spelling = spelling;
        this.ordering = ordering;
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
     * Whether values of this kind have an order.
     *
     * @return the ordering
     */
    public Ordering ordering() {
        return ordering;
    }

    /**
     * The kind one spelling names.
     *
     * @param spelling the spelling
     * @return the kind, or nothing where no kind is spelled that way
     */
    public static Optional<ScalarKind> named(String spelling) {
        return Arrays.stream(values())
                .filter(kind -> kind.spelling.equals(spelling))
                .findFirst();
    }
}
