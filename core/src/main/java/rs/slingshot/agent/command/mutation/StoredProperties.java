// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.property.PropertyScalar;

/**
 * A complete property map with the repository types the caller declared.
 *
 * <p>Conversion finishes before a handler writes any property. Strings, flags, integers,
 * decimals and dates use their native Java representations. A path uses the caller's JCR value
 * factory so a provider cannot infer that it is ordinary text. Multiple values keep a typed array,
 * including an array of one; mixed kinds and failed conversions publish no writable map.</p>
 */
public final class StoredProperties {

    private static final int NANOSECONDS_PER_MILLISECOND = 1_000_000;

    private StoredProperties() {
    }

    /** A complete converted map or a refusal before any repository change. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * Every assignment converted, in the caller's declared order.
     * @param properties the owned, read-only property map, ready for {@link #writeTo}
     */
    public record Held(Map<String, Object> properties) implements Outcome {

        /** Publishes a view of the completed conversion rather than another map copy. */
        public Held {
            properties = Collections.unmodifiableMap(properties);
        }

        /**
         * Stages every typed assignment on the caller's resource, without committing.
         * @param resource the resource belonging to the caller's resolver
         * @return a refusal if a provider cannot preserve a value; no commit has occurred
         */
        public Optional<Refused> writeTo(Resource resource) {
            return written(properties, resource);
        }
    }

    /**
     * A value or provider could not preserve the declared type.
     * @param detail a fixed explanation containing no supplied value
     */
    public record Refused(String detail) implements Outcome {
    }

    /**
     * Converts all assignments without modifying a resource or committing a session.
     * @param change the assignments, whose removal list is handled separately
     * @param resolver the caller's resolver, used only for its own path-value factory
     * @return the complete converted map, or a refusal with no partial map
     */
    public static Outcome of(PropertyChange change, ResourceResolver resolver) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        try {
            change.set().forEach((name, value) -> properties.put(name, stored(value, resolver)));
            return new Held(properties);
        } catch (final IllegalArgumentException refused) {
            return new Refused("a property cannot be stored with its declared kind and cardinality");
        }
    }

    private static Optional<Refused> written(Map<String, Object> properties, Resource resource) {
        final ModifiableValueMap values = resource.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            return Optional.of(new Refused("this resource does not support property assignments"));
        }
        final Node node = resource.adaptTo(Node.class);
        if (node != null) {
            return writtenToNode(properties, node);
        }
        if (properties.values().stream().anyMatch(value -> value instanceof Value
                || value instanceof Value[])) {
            return Optional.of(new Refused("this resource cannot preserve a JCR path kind"));
        }
        try {
            values.putAll(properties);
            return Optional.empty();
        } catch (final IllegalArgumentException refused) {
            return Optional.of(new Refused("the resource refused a typed property assignment"));
        }
    }

    private static Optional<Refused> writtenToNode(Map<String, Object> properties, Node node) {
        try {
            final ValueFactory factory = node.getSession().getValueFactory();
            for (final Map.Entry<String, Object> property : properties.entrySet()) {
                writtenProperty(node, property.getKey(), property.getValue(), factory);
            }
            return Optional.empty();
        } catch (final RepositoryException | IllegalArgumentException refused) {
            return Optional.of(new Refused("the repository refused a typed property assignment"));
        }
    }

    private static void writtenProperty(Node node, String name, Object value, ValueFactory factory)
            throws RepositoryException {
        if (value instanceof final Object[] values) {
            final Value[] converted = Arrays.stream(values)
                    .map(held -> repositoryValue(held, factory)).toArray(Value[]::new);
            if (converted.length == 0) {
                throw new IllegalArgumentException("a multiple property has at least one value");
            }
            removedConflicting(node, name, converted, converted[0].getType());
            node.setProperty(name, converted, converted[0].getType());
            return;
        }
        final Value converted = repositoryValue(value, factory);
        removedConflicting(node, name, converted, converted.getType());
        node.setProperty(name, converted, converted.getType());
    }

    private static void removedConflicting(Node node, String name, Object value, int kind)
            throws RepositoryException {
        if (!node.hasProperty(name)) {
            return;
        }
        final Property previous = node.getProperty(name);
        if (previous.getType() != kind || previous.isMultiple() != (value instanceof Object[])) {
            previous.remove();
        }
    }

    private static Value repositoryValue(Object value, ValueFactory factory) {
        return switch (value) {
            case String text -> factory.createValue(text);
            case Boolean truth -> factory.createValue(truth);
            case Long number -> factory.createValue(number);
            case BigDecimal decimal -> factory.createValue(decimal);
            case Calendar date -> factory.createValue(date);
            case Value path -> path;
            default -> throw new IllegalArgumentException("no repository kind is declared for this value");
        };
    }

    private static Object stored(PropertyValue value, ResourceResolver resolver) {
        final List<PropertyScalar> scalars = value.values();
        if (scalars.isEmpty() || scalars.stream().map(PropertyScalar::kind).distinct().count() != 1) {
            throw new IllegalArgumentException("a property has one kind and at least one value");
        }
        if (value instanceof PropertyValue.Single) {
            return scalar(scalars.getFirst(), resolver);
        }
        final List<Object> converted = scalars.stream().map(held -> scalar(held, resolver)).toList();
        return switch (scalars.getFirst().kind()) {
            case STRING -> converted.stream().map(String.class::cast).toArray(String[]::new);
            case BOOLEAN -> converted.stream().map(Boolean.class::cast).toArray(Boolean[]::new);
            case INTEGER -> converted.stream().map(Long.class::cast).toArray(Long[]::new);
            case DECIMAL -> converted.stream().map(BigDecimal.class::cast).toArray(BigDecimal[]::new);
            case DATE_TIME -> converted.stream().map(Calendar.class::cast).toArray(Calendar[]::new);
            case REPOSITORY_PATH -> converted.stream().map(Value.class::cast).toArray(Value[]::new);
        };
    }

    private static Object scalar(PropertyScalar scalar, ResourceResolver resolver) {
        return switch (scalar.kind()) {
            case STRING -> scalar.value();
            case BOOLEAN -> truth(scalar.value());
            case INTEGER -> Long.valueOf(scalar.value());
            case DECIMAL -> new BigDecimal(scalar.value());
            case DATE_TIME -> date(scalar.value());
            case REPOSITORY_PATH -> path(scalar.value(), resolver);
        };
    }

    private static Boolean truth(String text) {
        return switch (text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("a flag is true or false");
        };
    }

    private static Calendar date(String text) {
        final Instant instant;
        try {
            instant = Instant.parse(text);
        } catch (final java.time.format.DateTimeParseException refused) {
            throw new IllegalArgumentException("a date is a canonical UTC instant", refused);
        }
        if (instant.getNano() % NANOSECONDS_PER_MILLISECOND != 0
                || !instant.toString().equals(text)) {
            throw new IllegalArgumentException("a date preserves canonical millisecond precision");
        }
        return GregorianCalendar.from(instant.atZone(ZoneOffset.UTC));
    }

    private static Value path(String text, ResourceResolver resolver) {
        // Borrow the request's own session; the platform owns it and this conversion must not close it.
        if (!(resolver.adaptTo(Session.class) instanceof final Session session)) {
            throw new IllegalArgumentException("a path needs the caller's JCR value factory");
        }
        try {
            return session.getValueFactory().createValue(text, PropertyType.PATH);
        } catch (final RepositoryException refused) {
            throw new IllegalArgumentException("the repository refused a path value", refused);
        }
    }
}
