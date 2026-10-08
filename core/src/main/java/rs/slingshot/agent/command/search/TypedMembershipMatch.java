// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;

/** Matches large memberships with storage bounded by the caller's operand count. */
final class TypedMembershipMatch {

    private static final int MINIMUM_INDEXED_VALUES = 16;

    private TypedMembershipMatch() {
    }

    /**
     * Selects a lookup where repeated comparisons would dominate a linear scan.
     * @param predicate the caller's membership
     * @param stored the native multivalue
     * @return whether both collections contain at least sixteen values
     */
    static boolean warranted(PropertyPredicate.Membership predicate, PropertyValue.Multiple stored) {
        return predicate.values().size() >= MINIMUM_INDEXED_VALUES
                && stored.values().size() >= MINIMUM_INDEXED_VALUES;
    }

    /**
     * Evaluates a large membership without retaining keys for the stored property.
     * @param predicate the caller's membership, retaining its operator
     * @param stored the native multivalue, whose duplicates do not change membership
     * @return whether any operand occurs or every operand occurs
     */
    static boolean of(PropertyPredicate.Membership predicate, PropertyValue.Multiple stored) {
        final boolean all = predicate.operator() == PredicateOperator.LIST_CONTAINS_ALL;
        if (!all && TypedScalarComparison.equal(stored.values().getFirst(),
                predicate.values().getFirst())) {
            return true;
        }
        final List<PropertyScalar> comparable = predicate.values().stream()
                .map(TypedMembershipMatch::key).flatMap(Optional::stream).toList();
        if (all && comparable.size() != predicate.values().size()) {
            return false;
        }
        final Set<PropertyScalar> remaining = new HashSet<>(comparable);
        if (remaining.isEmpty()) {
            return false;
        }
        return all ? stored.values().stream().anyMatch(value -> consumed(remaining, value))
                : stored.values().stream().map(TypedMembershipMatch::key)
                        .flatMap(Optional::stream).anyMatch(remaining::contains);
    }

    private static boolean consumed(Set<PropertyScalar> remaining, PropertyScalar value) {
        key(value).ifPresent(remaining::remove);
        return remaining.isEmpty();
    }

    private static Optional<PropertyScalar> key(PropertyScalar value) {
        try {
            return switch (value.kind()) {
                case STRING, REPOSITORY_PATH -> Optional.of(value);
                case BOOLEAN -> Optional.of(new PropertyScalar(value.kind(),
                        Boolean.toString(Boolean.parseBoolean(value.value()))));
                case INTEGER -> Optional.of(new PropertyScalar(value.kind(),
                        Long.toString(Long.parseLong(value.value()))));
                case DECIMAL -> Optional.of(new PropertyScalar(value.kind(),
                        new BigDecimal(value.value()).stripTrailingZeros().toString()));
                case DATE_TIME -> Optional.of(new PropertyScalar(value.kind(),
                        Instant.parse(value.value()).toString()));
            };
        } catch (final NumberFormatException | DateTimeParseException invalid) {
            return Optional.empty();
        }
    }
}
