// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.util.List;
import java.util.stream.IntStream;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;

/** Evaluates the shared predicate model on a native property observation. */
final class TypedPredicateMatch {

    private TypedPredicateMatch() {
    }

    /**
     * Answers one structural predicate against a native observation.
     * @param predicate the caller's typed question
     * @param stored the property readable under current caller authority
     * @return whether its kind, cardinality and values satisfy the question
     */
    static boolean of(PropertyPredicate predicate, ObservedProperty stored) {
        return switch (predicate) {
            case PropertyPredicate.Presence ignored -> !(stored instanceof ObservedProperty.Absent);
            case PropertyPredicate.Equality equality ->
                    equality(equality.operator(), equality.value(), stored);
            case PropertyPredicate.Comparison comparison -> comparison(comparison, stored);
            case PropertyPredicate.Membership membership -> membership(membership, stored);
        };
    }

    private static boolean equality(PredicateOperator operator, PropertyValue asked,
                                    ObservedProperty stored) {
        if (!(stored instanceof final ObservedProperty.Held held)) {
            return false;
        }
        final boolean equal = same(held.value(), asked);
        return operator == PredicateOperator.EQUALS ? equal : !equal;
    }

    private static boolean same(PropertyValue stored, PropertyValue asked) {
        if (stored.getClass() != asked.getClass() || stored.values().size() != asked.values().size()) {
            return false;
        }
        final List<PropertyScalar> held = stored.values();
        final List<PropertyScalar> wanted = asked.values();
        return IntStream.range(0, held.size()).allMatch(position ->
                TypedScalarComparison.equal(held.get(position), wanted.get(position)));
    }

    private static boolean comparison(PropertyPredicate.Comparison predicate, ObservedProperty stored) {
        if (predicate.operator().comparand() == PredicateOperator.Comparand.ONE_VALUE) {
            return equality(predicate.operator(), new PropertyValue.Single(predicate.value()), stored);
        }
        if (!(stored instanceof final ObservedProperty.Held held)
                || !(held.value() instanceof final PropertyValue.Single single)) {
            return false;
        }
        return TypedScalarComparison.order(single.scalar(), predicate.value())
                .map(order -> ordered(predicate.operator(), order)).orElse(false);
    }

    private static boolean ordered(PredicateOperator operator, int order) {
        return switch (operator) {
            case LESS_THAN -> order < 0;
            case LESS_THAN_OR_EQUAL -> order <= 0;
            case GREATER_THAN -> order > 0;
            case GREATER_THAN_OR_EQUAL -> order >= 0;
            default -> false;
        };
    }

    private static boolean membership(PropertyPredicate.Membership predicate, ObservedProperty stored) {
        if (!(stored instanceof final ObservedProperty.Held held)) {
            return false;
        }
        if (predicate.operator() == PredicateOperator.SCALAR_IN) {
            return held.value() instanceof final PropertyValue.Single single
                    && predicate.values().stream().anyMatch(value ->
                            TypedScalarComparison.equal(single.scalar(), value));
        }
        if (!(held.value() instanceof final PropertyValue.Multiple multiple)) {
            return false;
        }
        if (TypedMembershipMatch.warranted(predicate, multiple)) {
            return TypedMembershipMatch.of(predicate, multiple);
        }
        return predicate.operator() == PredicateOperator.LIST_CONTAINS_ALL
                ? predicate.values().stream().allMatch(value -> contains(multiple.values(), value))
                : predicate.values().stream().anyMatch(value -> contains(multiple.values(), value));
    }

    private static boolean contains(List<PropertyScalar> stored, PropertyScalar asked) {
        return stored.stream().anyMatch(value -> TypedScalarComparison.equal(value, asked));
    }
}
