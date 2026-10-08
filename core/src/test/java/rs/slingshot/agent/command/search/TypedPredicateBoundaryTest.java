// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.BoundedDocumentReader;
import rs.slingshot.agent.json.DocumentValue;

/** Invalid nested client values and exact child paths refuse before discovery. */
final class TypedPredicateBoundaryTest {

    private static final AgentContract CONTRACT = assertInstanceOf(AgentContract.Loaded.class,
            AgentContract.load()).contract();

    @Test
    void cardinalityEnvelopesAreClosedHomogeneousNonemptyAndTyped() {
        for (final String value : List.of(
                "{\"cardinality\":\"single\",\"value\":true}",
                "{\"cardinality\":\"single\",\"value\":{\"type\":\"boolean\",\"value\":\"false\"}}",
                "{\"cardinality\":\"single\",\"value\":{\"type\":\"integer\",\"value\":true}}",
                "{\"cardinality\":\"single\",\"value\":{\"type\":\"unknown\",\"value\":\"1\"}}",
                "{\"cardinality\":\"single\",\"value\":{\"type\":\"string\",\"value\":\"1\",\"extra\":true}}",
                "{\"cardinality\":\"multiple\",\"values\":[]}",
                "{\"cardinality\":\"multiple\",\"values\":[{\"type\":\"string\",\"value\":\"1\"},"
                        + "{\"type\":\"integer\",\"value\":\"1\"}]}",
                "{\"cardinality\":\"single\",\"value\":{\"type\":\"integer\",\"value\":\"1\"},"
                        + "\"extra\":true}",
                "{\"cardinality\":\"unknown\",\"value\":{\"type\":\"integer\",\"value\":\"1\"}}",
                "{\"cardinality\":\"single\",\"values\":[{\"type\":\"integer\",\"value\":\"1\"}]}",
                "{\"cardinality\":\"multiple\",\"values\":true}")) {
            assertInstanceOf(PropertyPredicate.Refused.class, read("equals", "synthetic", value), value);
        }
    }

    @Test
    void wrappedIntegersRequireMinimalSignedLongSpelling() {
        for (final String value : List.of("not-a-number", "+1", "01", "-0", "9223372036854775808")) {
            assertInstanceOf(PropertyPredicate.Refused.class,
                    read("equals", "synthetic", single("integer", value)), value);
        }
    }

    @Test
    void wrappedDecimalsRejectInvalidNumbersAndNoncanonicalSpellings() {
        for (final String value : List.of("invalid", "1E3", "01.5", "1.", ".5", "-0.00",
                "1".repeat(1025), "0." + "1".repeat(1025))) {
            assertInstanceOf(PropertyPredicate.Refused.class,
                    read("equals", "synthetic", single("decimal", value)), value);
        }
    }

    @Test
    void wrappedDatesRejectOffsetsInvalidCalendarDaysAndAlternateZeroSpellings() {
        for (final String value : List.of("invalid", "2026-02-30T00:00:00Z",
                "2026-01-01T00:00:00+00:00", "2026-01-01T00:00:00.000Z")) {
            assertInstanceOf(PropertyPredicate.Refused.class,
                    read("equals", "synthetic", single("date_time", value)), value);
        }
    }

    @Test
    void propertyAddressesCannotEscapeOrAliasTheCandidate() {
        for (final String path : List.of("../synthetic", "child/../synthetic", "child//synthetic",
                "child/", "./synthetic", "child/synthetic[2]", "child[1]/synthetic",
                "child[02]/synthetic", "child[9223372036854775808]/synthetic", "bad:prefix:name",
                " bad", "bad ", "e\u0301", "bad|name", "child[/synthetic", "/synthetic")) {
            assertInstanceOf(PropertyPredicate.Refused.class, read("exists", path, ""), path);
        }
        for (final String path : List.of("child[2]/synthetic", "jcr:content/synthetic", "é")) {
            assertInstanceOf(PropertyPredicate.Held.class, read("exists", path, ""), path);
        }
    }

    @Test
    void wrappedRepositoryPathsKeepCanonicalSegmentAndAddressBounds() {
        for (final String path : List.of("relative", "/child/../synthetic", "/child/",
                "/child//synthetic", "/bad|name", "/child[1]", "/child[2]extra",
                "/" + "x".repeat(4096), "/" + "x/".repeat(257) + "x")) {
            assertInstanceOf(PropertyPredicate.Refused.class,
                    read("equals", "synthetic", single("repository_path", path)), path);
        }
        assertInstanceOf(PropertyPredicate.Held.class,
                read("equals", "synthetic", single("repository_path", "/")));
    }

    @Test
    void membershipDuplicatesUseNumericEqualityRatherThanDecimalSpelling() {
        final String written = "{\"operator\":\"scalar_in\",\"property_path\":\"synthetic\",\"values\":["
                + "{\"type\":\"decimal\",\"value\":\"1.50\"},"
                + "{\"type\":\"decimal\",\"value\":\"1.5\"}]}";
        assertEquals(PropertyPredicate.Refusal.VALUES_NOT_UNIQUE,
                assertInstanceOf(PropertyPredicate.Refused.class,
                        PropertyPredicate.of(document(written), CONTRACT)).refusal());
    }

    @Test
    void invalidTypedScalarsHaveNoEqualityOrOrder() {
        final PropertyScalar invalid = new PropertyScalar(ScalarKind.INTEGER, "not-a-number");
        final PropertyScalar zero = new PropertyScalar(ScalarKind.INTEGER, "0");
        assertFalse(TypedScalarComparison.equal(invalid, invalid));
        assertTrue(TypedScalarComparison.order(invalid, zero).isEmpty());
        assertEquals(new PropertyScalar(ScalarKind.DECIMAL, "invalid"),
                TypedScalarComparison.equalityKey(new PropertyScalar(ScalarKind.DECIMAL, "invalid")));
        assertTrue(TypedScalarComparison.order(new PropertyScalar(ScalarKind.DATE_TIME, "invalid"),
                new PropertyScalar(ScalarKind.DATE_TIME, "2026-01-01T00:00:00Z")).isEmpty());
    }

    @Test
    void wholePropertyEqualityKeepsTheHistoricalStringListAdapterExplicit() {
        final PropertyScalar scalar = new PropertyScalar(ScalarKind.STRING, "one");
        final var single = new PropertyPredicate.Equality(PredicateOperator.EQUALS, "synthetic",
                new PropertyValue.Single(scalar));
        final var multiple = new PropertyPredicate.Equality(PredicateOperator.EQUALS, "synthetic",
                new PropertyValue.Multiple(List.of(scalar)));
        assertTrue(single.isSatisfiedBy(List.of("one")));
        assertTrue(multiple.isSatisfiedBy(List.of("one")));
        assertFalse(single.isSatisfiedBy(List.of("one", "one")));
        assertFalse(single.isSatisfiedBy(List.<String>of()));
    }

    private static PropertyPredicate.Outcome read(String operator, String path, String value) {
        return PropertyPredicate.of(document("{\"operator\":\"" + operator
                + "\",\"property_path\":\"" + path + "\""
                + (value.isEmpty() ? "" : ",\"value\":" + value) + "}"), CONTRACT);
    }

    private static String single(String kind, String text) {
        return "{\"cardinality\":\"single\",\"value\":{\"type\":\"" + kind
                + "\",\"value\":\"" + text + "\"}}";
    }

    private static DocumentValue document(String text) {
        return assertInstanceOf(BoundedDocumentReader.Read.class, BoundedDocumentReader.read(
                text.getBytes(StandardCharsets.UTF_8), new BoundedDocumentReader.Bounds(
                        100_000, 20, 20, 20_000))).value();
    }
}
