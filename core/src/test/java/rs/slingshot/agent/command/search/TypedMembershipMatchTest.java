// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;

/** Large memberships retain native equality, cardinality and short-circuit answers. */
final class TypedMembershipMatchTest {

    @Test
    void everyNativeKindMatchesLargeStoredCollections() {
        for (final ScalarKind kind : ScalarKind.values()) {
            final List<PropertyScalar> values = values(kind, 32);
            assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, values.subList(0, 16), values));
            assertTrue(matches(PredicateOperator.LIST_CONTAINS_ANY, values.subList(16, 32), values));
        }
    }

    @Test
    void firstLastAndMissingOperandsKeepAnyAndAllDistinct() {
        final List<PropertyScalar> stored = values(ScalarKind.INTEGER, 32);
        final List<PropertyScalar> wanted = IntStream.range(16, 32)
                .mapToObj(value -> scalar(ScalarKind.INTEGER, Integer.toString(value))).toList();
        final List<PropertyScalar> absent = IntStream.range(32, 48)
                .mapToObj(value -> scalar(ScalarKind.INTEGER, Integer.toString(value))).toList();
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, wanted, stored));
        assertFalse(matches(PredicateOperator.LIST_CONTAINS_ANY, absent, stored));
        assertFalse(matches(PredicateOperator.LIST_CONTAINS_ALL, absent, stored));
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ANY, stored.subList(0, 16), stored));
    }

    @Test
    void listOrderAndStoredDuplicatesDoNotChangeMembership() {
        final List<PropertyScalar> wanted = values(ScalarKind.STRING, 16);
        final List<PropertyScalar> stored = IntStream.range(0, 32)
                .mapToObj(value -> wanted.get(15 - value % 16)).toList();
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, wanted, stored));
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ANY, wanted, stored));
        assertFalse(new PropertyPredicate.Equality(PredicateOperator.EQUALS, "synthetic",
                new PropertyValue.Multiple(wanted)).isSatisfiedBy(held(stored)));
    }

    @Test
    void decimalScaleAndAlternateIntegerSpellingsKeepNumericEquality() {
        final List<PropertyScalar> decimals = values(ScalarKind.DECIMAL, 32);
        final List<PropertyScalar> asked = IntStream.range(0, 16)
                .mapToObj(value -> scalar(ScalarKind.DECIMAL, value + ".5000")).toList();
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, asked, decimals));
        final List<PropertyScalar> integers = values(ScalarKind.INTEGER, 32);
        final List<PropertyScalar> legacy = IntStream.range(0, 16)
                .mapToObj(value -> scalar(ScalarKind.INTEGER, "+" + value)).toList();
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, legacy, integers));
    }

    @Test
    void dateAliasesCompareInstantsAndDoNotCompareAcrossKinds() {
        final List<PropertyScalar> dates = values(ScalarKind.DATE_TIME, 32);
        final List<PropertyScalar> aliases = dates.subList(0, 16).stream()
                .map(value -> scalar(ScalarKind.DATE_TIME, value.value().replace("Z", "+00:00"))).toList();
        assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, aliases, dates));
        final List<PropertyScalar> text = aliases.stream()
                .map(value -> scalar(ScalarKind.STRING, value.value())).toList();
        assertFalse(matches(PredicateOperator.LIST_CONTAINS_ANY, text, dates));
    }

    @Test
    void malformedNumbersAndDatesNeverBecomeMatchingKeys() {
        for (final ScalarKind kind : List.of(ScalarKind.INTEGER, ScalarKind.DECIMAL, ScalarKind.DATE_TIME)) {
            final List<PropertyScalar> invalid = IntStream.range(0, 16)
                    .mapToObj(value -> scalar(kind, "invalid-" + value)).toList();
            final List<PropertyScalar> valid = values(kind, 32);
            assertFalse(matches(PredicateOperator.LIST_CONTAINS_ANY, invalid, invalid));
            assertFalse(matches(PredicateOperator.LIST_CONTAINS_ALL, invalid, valid));
            assertFalse(matches(PredicateOperator.LIST_CONTAINS_ANY, valid, invalid));
        }
    }

    @Test
    void missingEmptyAndSinglePropertiesRetainCardinalityRules() {
        final List<PropertyScalar> values = values(ScalarKind.STRING, 16);
        final var list = new PropertyPredicate.Membership(PredicateOperator.LIST_CONTAINS_ANY,
                "synthetic", values);
        assertFalse(list.isSatisfiedBy(ObservedProperty.Absent.INSTANCE));
        assertFalse(list.isSatisfiedBy(ObservedProperty.EmptyMultiple.INSTANCE));
        final var scalar = new ObservedProperty.Held(new PropertyValue.Single(values.getFirst()));
        assertFalse(list.isSatisfiedBy(scalar));
        final var single = new PropertyPredicate.Membership(PredicateOperator.SCALAR_IN, "synthetic", values);
        assertTrue(single.isSatisfiedBy(scalar));
        assertFalse(single.isSatisfiedBy(held(values)));
    }

    @Test
    void unicodeAndRepositoryAddressesRetainExactTextEquality() {
        for (final ScalarKind kind : List.of(ScalarKind.STRING, ScalarKind.REPOSITORY_PATH)) {
            final List<PropertyScalar> values = IntStream.range(0, 16)
                    .mapToObj(value -> scalar(kind, "/synthetic/\uD83D\uDE00/" + value)).toList();
            final List<PropertyScalar> different = IntStream.range(0, 16)
                    .mapToObj(value -> scalar(kind, "/synthetic/\uE000/" + value)).toList();
            assertTrue(matches(PredicateOperator.LIST_CONTAINS_ALL, values, values));
            assertFalse(matches(PredicateOperator.LIST_CONTAINS_ANY, different, values));
        }
    }

    private static boolean matches(PredicateOperator operator, List<PropertyScalar> wanted,
                                   List<PropertyScalar> stored) {
        return new PropertyPredicate.Membership(operator, "synthetic", wanted).isSatisfiedBy(held(stored));
    }

    private static ObservedProperty held(List<PropertyScalar> values) {
        return new ObservedProperty.Held(new PropertyValue.Multiple(values));
    }

    private static PropertyScalar scalar(ScalarKind kind, String value) {
        return new PropertyScalar(kind, value);
    }

    private static List<PropertyScalar> values(ScalarKind kind, int count) {
        return IntStream.range(0, count).mapToObj(value -> scalar(kind, switch (kind) {
            case STRING -> "synthetic-" + value;
            case BOOLEAN -> Boolean.toString(value % 2 != 0);
            case INTEGER -> Integer.toString(value);
            case DECIMAL -> value + ".50";
            case DATE_TIME -> Instant.parse("2026-01-01T00:00:00Z").plusSeconds(value).toString();
            case REPOSITORY_PATH -> "/synthetic/" + value;
        })).toList();
    }
}
