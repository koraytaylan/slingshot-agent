// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.Value;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.command.search.ObservedProperty;
import rs.slingshot.agent.command.search.PropertyPredicate;

/** Native provider values and unreadable properties never become string conversions. */
final class PredicatePropertyReaderTest {

    private static final String NAME = "synthetic_property";

    @Test
    void theHistoricalPublicTextAdapterKeepsItsScalarListAndMissingPropertyBehavior() {
        assertEquals(List.of("one"), QueryPathsHandler.storedAt(memory(Map.of(NAME, "one")), NAME));
        assertEquals(List.of("two", "one"), QueryPathsHandler.storedAt(
                memory(Map.of(NAME, new String[] {"two", "one"})), NAME));
        assertEquals(List.of(), QueryPathsHandler.storedAt(memory(Map.of()), NAME));
        assertEquals(List.of(), QueryPathsHandler.storedAt(memory(Map.of()), "missing/" + NAME));
        final Resource child = memory(Map.of(NAME, "inside"));
        final Resource parent = proxy(Resource.class, method -> {
            if ("getChild".equals(method)) {
                return child;
            }
            throw new AssertionError("unexpected historical adapter access");
        });
        assertEquals(List.of("inside"), QueryPathsHandler.storedAt(parent, "child/" + NAME));
    }

    @Test
    void nativeScalarsRetainTheirKinds() {
        final var date = Calendar.getInstance();
        date.setTimeInMillis(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        final Map<Object, PropertyScalar> values = Map.of(
                "false", new PropertyScalar(ScalarKind.STRING, "false"),
                Boolean.FALSE, new PropertyScalar(ScalarKind.BOOLEAN, "false"),
                (byte) 1, new PropertyScalar(ScalarKind.INTEGER, "1"),
                (short) 2, new PropertyScalar(ScalarKind.INTEGER, "2"),
                3, new PropertyScalar(ScalarKind.INTEGER, "3"),
                4L, new PropertyScalar(ScalarKind.INTEGER, "4"),
                new BigDecimal("1.50"), new PropertyScalar(ScalarKind.DECIMAL, "1.50"),
                date, new PropertyScalar(ScalarKind.DATE_TIME, "2026-01-01T00:00:00Z"));
        values.forEach((nativeValue, expected) -> assertEquals(
                new ObservedProperty.Held(new PropertyValue.Single(expected)), observed(nativeValue)));
    }

    @Test
    void nativeArraysRetainListCardinalityIncludingOneElement() {
        for (final Object nativeValue : List.of(new String[] {"1"}, new boolean[] {true},
                new short[] {1}, new int[] {1}, new long[] {1}, new Byte[] {(byte) 1},
                new BigDecimal[] {BigDecimal.ONE})) {
            final var held = assertInstanceOf(ObservedProperty.Held.class, observed(nativeValue));
            assertInstanceOf(PropertyValue.Multiple.class, held.value());
            assertEquals(1, held.value().values().size());
        }
        final var held = assertInstanceOf(ObservedProperty.Held.class, observed(new String[] {"b", "a"}));
        assertEquals(List.of(new PropertyScalar(ScalarKind.STRING, "b"),
                new PropertyScalar(ScalarKind.STRING, "a")), held.value().values());
    }

    @Test
    void emptyPresentPropertiesDifferFromAbsence() {
        final var presence = new PropertyPredicate.Presence(NAME);
        assertEquals(ObservedProperty.EmptyMultiple.INSTANCE, observed(new String[0]));
        assertTrue(presence.isSatisfiedBy(observed(new String[0])));
        assertEquals(ObservedProperty.Absent.INSTANCE,
                PredicatePropertyReader.at(memory(Map.of()), NAME));
        assertFalse(presence.isSatisfiedBy(PredicatePropertyReader.at(memory(Map.of()), NAME)));
        assertEquals(ObservedProperty.Absent.INSTANCE,
                PredicatePropertyReader.at(memory(Map.of()), "missing/" + NAME));
    }

    @Test
    void unsupportedNativeKindsArePresentWithoutBeingConverted() {
        for (final Object value : List.of(1.0, new byte[0], new double[] {1.0},
                new Object[] {"1", 1L}, new String[] {null}, new Object())) {
            assertEquals(ObservedProperty.Unrepresented.INSTANCE, observed(value));
            assertTrue(new PropertyPredicate.Presence(NAME).isSatisfiedBy(observed(value)));
        }
    }

    @Test
    void relativePathsResolveOnlyTheExactChildAndCannotEscapeTheCandidate() {
        final Resource child = memory(Map.of(NAME, "inside"));
        final Resource parent = proxy(Resource.class, method -> {
            if ("getChild".equals(method)) {
                return child;
            }
            throw new AssertionError("unexpected parent access");
        });
        assertEquals(observed("inside"), PredicatePropertyReader.at(parent, "child/" + NAME));
        for (final String path : List.of("../" + NAME, "./" + NAME, "/" + NAME,
                "child//" + NAME, "child/../" + NAME, "child/")) {
            assertEquals(ObservedProperty.Absent.INSTANCE, PredicatePropertyReader.at(parent, path));
        }
    }

    @Test
    void unsupportedSingleJcrKindsNeverReadTheirContents() {
        final List<String> calls = new ArrayList<>();
        final Property property = proxy(Property.class, method -> {
            calls.add(method);
            return switch (method) {
                case "isMultiple" -> false;
                case "getType" -> PropertyType.BINARY;
                default -> throw new AssertionError("binary contents were accessed");
            };
        });
        assertEquals(ObservedProperty.Unrepresented.INSTANCE,
                PredicatePropertyReader.at(repository(property), NAME));
        assertEquals(List.of("isMultiple", "getType"), calls);
    }

    @Test
    void unsupportedMultipleJcrKindsAreNotConverted() {
        final Property property = proxy(Property.class, method -> switch (method) {
            case "isMultiple" -> true;
            case "getValues" -> new Value[] {proxy(Value.class,
                    access -> {
                        throw new AssertionError("unsupported value was converted");
                    })};
            case "getType" -> PropertyType.DOUBLE;
            default -> throw new AssertionError("unexpected multiple property access");
        });
        assertEquals(ObservedProperty.Unrepresented.INSTANCE,
                PredicatePropertyReader.at(repository(property), NAME));
    }

    @Test
    void unreadableJcrValuesCanOnlyFallBackToCurrentCallerNativeProperties() {
        final Value unreadable = Value.class.cast(Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[]{Value.class},
                (held, method, arguments) -> {
                    throw new javax.jcr.RepositoryException("synthetic");
                }));
        final Property property = proxy(Property.class, method -> switch (method) {
            case "isMultiple" -> false;
            case "getType" -> PropertyType.LONG;
            case "getValue" -> unreadable;
            default -> throw new AssertionError("unexpected unreadable property access");
        });
        assertEquals(ObservedProperty.Absent.INSTANCE,
                PredicatePropertyReader.at(repository(property), NAME));
    }

    private static ObservedProperty observed(Object value) {
        return PredicatePropertyReader.at(memory(Map.of(NAME, value)), NAME);
    }

    private static Resource memory(Map<String, Object> values) {
        return proxy(Resource.class, method -> switch (method) {
            case "adaptTo", "getChild" -> null;
            case "getValueMap" -> new ValueMapDecorator(values);
            default -> throw new AssertionError("unexpected native provider access");
        });
    }

    private static Resource repository(Property property) {
        final Node node = proxy(Node.class, method -> switch (method) {
            case "hasProperty" -> true;
            case "getProperty" -> property;
            default -> throw new AssertionError("unexpected node access");
        });
        return proxy(Resource.class, method -> switch (method) {
            case "adaptTo" -> node;
            case "getValueMap" -> new ValueMapDecorator(Map.of());
            default -> throw new AssertionError("unexpected repository provider access");
        });
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type}, (held, method, arguments) -> answer.apply(method.getName())));
    }
}
