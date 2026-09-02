// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What one predicate asks about a property.
 *
 * <p>Ten operators and no query text. A caller cannot send a query language of any kind, because a
 * string that reaches a query engine is a string that can address content the command was never
 * pointed at. What can be asked is exactly this list.</p>
 *
 * <p>Each operator says what it takes beside a property, and that is checked when a predicate is
 * read: an {@code exists} carrying a value and a {@code less_than} carrying none are both callers
 * who meant something else, and answering either would mean guessing which.</p>
 */
public enum PredicateOperator {

    /** Whether the property is there at all. */
    EXISTS("exists", Comparand.NOTHING),

    /** Whether it holds exactly one value. */
    EQUALS("equals", Comparand.ONE_VALUE),

    /** Whether it holds anything else. */
    NOT_EQUALS("not_equals", Comparand.ONE_VALUE),

    /** Whether a scalar property is one of several values. */
    SCALAR_IN("scalar_in", Comparand.SEVERAL_VALUES),

    /** Whether a list property holds any of several values. */
    LIST_CONTAINS_ANY("list_contains_any", Comparand.SEVERAL_VALUES),

    /** Whether it holds all of them. */
    LIST_CONTAINS_ALL("list_contains_all", Comparand.SEVERAL_VALUES),

    /** Whether a scalar property sorts before a value. */
    LESS_THAN("less_than", Comparand.ONE_ORDERED_VALUE),

    /** Whether it sorts before or equal to one. */
    LESS_THAN_OR_EQUAL("less_than_or_equal", Comparand.ONE_ORDERED_VALUE),

    /** Whether it sorts after a value. */
    GREATER_THAN("greater_than", Comparand.ONE_ORDERED_VALUE),

    /** Whether it sorts after or equal to one. */
    GREATER_THAN_OR_EQUAL("greater_than_or_equal", Comparand.ONE_ORDERED_VALUE);

    /** What an operator takes beside the property it is about. */
    public enum Comparand {
        /** Nothing at all. */
        NOTHING,
        /** One value of any kind. */
        ONE_VALUE,
        /** One value of a kind that has an order. */
        ONE_ORDERED_VALUE,
        /** Several values, all of one kind. */
        SEVERAL_VALUES
    }

    private final String spelling;
    private final Comparand comparand;

    PredicateOperator(String spelling, Comparand comparand) {
        this.spelling = spelling;
        this.comparand = comparand;
    }

    /**
     * How this operator is spelled on the wire.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * What this operator takes beside a property.
     *
     * @return the comparand
     */
    public Comparand comparand() {
        return comparand;
    }

    /**
     * The operator one spelling names.
     *
     * @param spelling the spelling
     * @return the operator, or nothing where no operator is spelled that way
     */
    public static Optional<PredicateOperator> named(String spelling) {
        return Arrays.stream(values())
                .filter(operator -> operator.spelling.equals(spelling))
                .findFirst();
    }

    /**
     * Every operator's spelling, in the order they are declared.
     *
     * @return the spellings, which a refusal names so a caller can see what can be asked
     */
    public static List<String> spellings() {
        return Arrays.stream(values())
                .map(PredicateOperator::spelling)
                .toList();
    }
}
