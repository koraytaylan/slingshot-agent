// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;

/** Numeric discovery never turns an invalid value into a matching zero. */
final class NumericPredicateComparisonTest {

    private static final String INVALID = "synthetic-invalid";

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidStoredValuesDoNotEqualZero(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.EQUALS, "0", INVALID));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidAskedValuesDoNotEqualZero(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.EQUALS, INVALID, "0"));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidStoredValuesDoNotSortBeforePositiveNumbers(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.LESS_THAN, "1", INVALID));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidStoredValuesDoNotSortAfterNegativeNumbers(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.GREATER_THAN, "-1", INVALID));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidAskedValuesDoNotSortAfterNegativeNumbers(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.LESS_THAN, INVALID, "-1"));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidAskedValuesDoNotSortBeforePositiveNumbers(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.GREATER_THAN, INVALID, "1"));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidStoredValuesDoNotJoinZeroMembership(ScalarKind kind) {
        final PropertyPredicate predicate = new PropertyPredicate.Membership(
                PredicateOperator.SCALAR_IN, "synthetic_property", List.of(new PropertyScalar(kind, "0")));
        assertFalse(predicate.isSatisfiedBy(List.of(INVALID)));
        assertTrue(predicate.isSatisfiedBy(List.of(INVALID, "0")));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidStoredValuesCannotProveNumericDifference(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.NOT_EQUALS, "1", INVALID));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void invalidAskedValuesCannotProveNumericDifference(ScalarKind kind) {
        assertFalse(compare(kind, PredicateOperator.NOT_EQUALS, INVALID, "1"));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void anAbsentPropertyCannotProveNumericDifference(ScalarKind kind) {
        final PropertyPredicate predicate = new PropertyPredicate.Comparison(
                PredicateOperator.NOT_EQUALS, "synthetic_property", new PropertyScalar(kind, "1"));
        assertFalse(predicate.isSatisfiedBy(List.of()));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void validNumbersRetainTheirOrderAndEquality(ScalarKind kind) {
        assertTrue(compare(kind, PredicateOperator.GREATER_THAN, "9", "10"));
        assertTrue(compare(kind, PredicateOperator.LESS_THAN, "0", "-1"));
        assertTrue(compare(kind, PredicateOperator.EQUALS, "0", "0"));
        assertTrue(compare(kind, PredicateOperator.NOT_EQUALS, "0", "1"));
        assertFalse(compare(kind, PredicateOperator.NOT_EQUALS, "0", "0"));
    }

    @ParameterizedTest
    @EnumSource(value = ScalarKind.class, names = {"INTEGER", "DECIMAL"})
    void aScalarWithNoNumericOrderRefusesRatherThanReportingZero(ScalarKind kind) {
        assertThrows(NumberFormatException.class, () -> new PropertyScalar(kind, "0").compareWith(INVALID));
        assertThrows(NumberFormatException.class, () -> new PropertyScalar(kind, INVALID).compareWith("0"));
    }

    @Test
    void anOverflowingIntegerDoesNotEqualZero() {
        assertFalse(compare(ScalarKind.INTEGER, PredicateOperator.EQUALS, "0", "9223372036854775808"));
    }

    @Test
    void decimalsCompareNumericallyAcrossScales() {
        assertTrue(compare(ScalarKind.DECIMAL, PredicateOperator.EQUALS, "1.50", "1.5"));
    }

    @Test
    void decimalExponentsNeedNoExpansionToPlainText() {
        assertTrue(compare(ScalarKind.DECIMAL, PredicateOperator.GREATER_THAN, "1E+999", "1E+1000"));
    }

    private static boolean compare(ScalarKind kind, PredicateOperator operator, String asked, String stored) {
        return new PropertyPredicate.Comparison(operator, "synthetic_property",
                new PropertyScalar(kind, asked))
                .isSatisfiedBy(List.of(stored));
    }
}
