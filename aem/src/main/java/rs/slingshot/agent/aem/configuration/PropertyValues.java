// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.aem.configuration;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import rs.slingshot.agent.command.platform.ConfigurationValue;

/**
 * How a configuration property's Java value reads as the client's typed value, and back.
 *
 * <p>The type and the cardinality both survive the round trip: an {@code int[]} read here is
 * written back as an {@code int[]}, and never as a {@code Integer[]} or a list, because the
 * service reading it would see a different configuration.</p>
 */
final class PropertyValues {

    /** The client's spelling of each boxed type a property may hold. */
    private static final Map<Class<?>, String> SPELLINGS = Map.of(String.class, "string",
            Boolean.class, "boolean", Character.class, "character", Byte.class, "byte",
            Short.class, "short", Integer.class, "integer", Long.class, "long", Float.class,
            "float", Double.class, "double");

    /** How each spelled type is read back from its text. */
    private static final Map<String, Function<String, Object>> PARSERS = Map.of(
            "string", text -> text,
            "boolean", PropertyValues::bool,
            "character", PropertyValues::character,
            "byte", Byte::valueOf,
            "short", Short::valueOf,
            "integer", Integer::valueOf,
            "long", Long::valueOf,
            "float", Float::valueOf,
            "double", Double::valueOf);

    /** The primitive type each spelled type is held as in a primitive array. */
    private static final Map<String, Class<?>> PRIMITIVES = Map.of("boolean", boolean.class,
            "character", char.class, "byte", byte.class, "short", short.class, "integer",
            int.class, "long", long.class, "float", float.class, "double", double.class);

    /** The boxed type each spelled type is held as in a boxed array. */
    private static final Map<String, Class<?>> BOXES = Map.of("string", String.class,
            "boolean", Boolean.class, "character", Character.class, "byte", Byte.class,
            "short", Short.class, "integer", Integer.class, "long", Long.class, "float",
            Float.class, "double", Double.class);

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
                values.add(String.valueOf(element));
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
                    .mapToObj(index -> String.valueOf(Array.get(held, index)))
                    .toList();
            return spelled.map(type -> new ConfigurationValue(type, component.isPrimitive()
                    ? ConfigurationValue.Cardinality.PRIMITIVE_ARRAY
                    : ConfigurationValue.Cardinality.SCALAR_ARRAY, values));
        }
        return spelling(held.getClass()).map(type -> new ConfigurationValue(type,
                ConfigurationValue.Cardinality.SCALAR, List.of(String.valueOf(held))));
    }

    /**
     * One of the client's typed values as the Java value a configuration holds.
     *
     * @param value what the client wrote
     * @return the Java value
     * @throws IllegalArgumentException where a value does not read as its type, or the type is
     *     not one a configuration may have
     */
    static Object written(ConfigurationValue value) {
        final Function<String, Object> parser = PARSERS.get(value.type());
        if (parser == null) {
            throw new IllegalArgumentException(value.type() + " is not a configuration type");
        }
        final List<Object> parsed = value.values().stream().map(parser).toList();
        return switch (value.cardinality()) {
            case SCALAR -> parsed.getFirst();
            case COLLECTION -> List.copyOf(parsed);
            case PRIMITIVE_ARRAY -> array(PRIMITIVES.get(value.type()), value.type(), parsed);
            case SCALAR_ARRAY -> array(BOXES.get(value.type()), value.type(), parsed);
        };
    }

    private static Object array(Class<?> component, String type, List<Object> parsed) {
        if (component == null) {
            throw new IllegalArgumentException(type + " has no primitive array");
        }
        final Object array = Array.newInstance(component, parsed.size());
        IntStream.range(0, parsed.size()).forEach(index -> Array.set(array, index,
                parsed.get(index)));
        return array;
    }

    private static Optional<String> spelling(Class<?> type) {
        return Optional.ofNullable(SPELLINGS.get(type));
    }

    private static Object bool(String text) {
        if (!"true".equals(text) && !"false".equals(text)) {
            throw new IllegalArgumentException(text + " is neither true nor false");
        }
        return Boolean.valueOf(text);
    }

    private static Object character(String text) {
        if (text.length() != 1) {
            throw new IllegalArgumentException("a character is exactly one long");
        }
        return text.charAt(0);
    }
}
