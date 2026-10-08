// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.configuration;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import rs.slingshot.agent.command.platform.ConfigurationValue;

/**
 * How a configuration property's Java value reads as the client's typed value.
 *
 * <p>The type and the cardinality are both reported: an {@code int[]} reads as a primitive array,
 * and never as a {@code Integer[]} or a list, because the service reading it sees a different
 * configuration.</p>
 */
final class PropertyValues {

    /** How many hexadecimal digits a float's bits are spelled with. */
    private static final int FLOAT_DIGITS = 8;

    /** How many hexadecimal digits a double's bits are spelled with. */
    private static final int DOUBLE_DIGITS = 16;

    /** The client's spelling of each boxed type a property may hold. */
    private static final Map<Class<?>, String> SPELLINGS = Map.of(String.class, "string",
            Boolean.class, "boolean", Character.class, "character", Byte.class, "byte",
            Short.class, "short", Integer.class, "integer", Long.class, "long", Float.class,
            "float", Double.class, "double");

    /** What an empty collection's element type is taken to be, since it has no element. */
    private static final String EMPTY_COLLECTION_TYPE = "string";

    private PropertyValues() {
    }

    /**
     * One property's value in the client's words.
     *
     * @param held what the platform holds
     * @return the value, or nothing where its type is not one a configuration may have
     */
    static Optional<ConfigurationValue> read(Object held) {
        if (held instanceof final Collection<?> collection) {
            final List<String> values = new ArrayList<>();
            String type = EMPTY_COLLECTION_TYPE;
            for (final Object element : collection) {
                final Optional<String> spelled = spelling(element.getClass());
                if (spelled.isEmpty()) {
                    return Optional.empty();
                }
                type = spelled.get();
                values.add(spelledValue(element));
            }
            return Optional.of(new ConfigurationValue(type,
                    ConfigurationValue.Cardinality.COLLECTION, values));
        }
        if (held.getClass().isArray()) {
            final Class<?> component = held.getClass().getComponentType();
            final Optional<String> spelled = component.isPrimitive()
                    ? spelling(Array.get(Array.newInstance(component, 1), 0).getClass())
                    : spelling(component);
            final List<String> values = IntStream.range(0, Array.getLength(held))
                    .mapToObj(index -> spelledValue(Array.get(held, index)))
                    .toList();
            return spelled.map(type -> new ConfigurationValue(type, component.isPrimitive()
                    ? ConfigurationValue.Cardinality.PRIMITIVE_ARRAY
                    : ConfigurationValue.Cardinality.SCALAR_ARRAY, values));
        }
        return spelling(held.getClass()).map(type -> new ConfigurationValue(type,
                ConfigurationValue.Cardinality.SCALAR, List.of(spelledValue(held))));
    }

    /**
     * One value as the client spells it: a floating value as the lowercase hexadecimal of its
     * exact bits, so no value changes in a round trip, and everything else as Java writes it.
     */
    private static String spelledValue(Object value) {
        if (value instanceof final Float single) {
            return String.format("%0" + FLOAT_DIGITS + "x", Float.floatToRawIntBits(single));
        }
        if (value instanceof final Double twice) {
            return String.format("%0" + DOUBLE_DIGITS + "x", Double.doubleToRawLongBits(twice));
        }
        return String.valueOf(value);
    }

    private static Optional<String> spelling(Class<?> type) {
        return Optional.ofNullable(SPELLINGS.get(type));
    }
}
