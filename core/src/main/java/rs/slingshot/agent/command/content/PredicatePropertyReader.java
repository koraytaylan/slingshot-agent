// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import org.apache.sling.api.resource.Resource;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.command.property.ScalarKind;
import rs.slingshot.agent.command.search.ObservedProperty;

/** Exact, caller-scoped native property observations for both discovery commands. */
final class PredicatePropertyReader {

    private PredicatePropertyReader() {
    }

    /**
     * Reads one exact property without changing caller authority or coercing native values.
     * @param candidate the resource already admitted by the discovery cursor
     * @param propertyPath the validated relative child address and final property name
     * @return readable absence, an empty multivalue, an unsupported kind or the complete typed value
     */
    static ObservedProperty at(Resource candidate, String propertyPath) {
        // A child address may never traverse back above the candidate, including for direct API use.
        if (Arrays.stream(propertyPath.split("/", -1))
                .anyMatch(segment -> segment.isEmpty() || ".".equals(segment) || "..".equals(segment))) {
            return ObservedProperty.Absent.INSTANCE;
        }
        final int lastSlash = propertyPath.lastIndexOf('/');
        final Optional<Resource> holding = lastSlash < 0 ? Optional.of(candidate)
                : Optional.ofNullable(candidate.getChild(propertyPath.substring(0, lastSlash)));
        return holding.map(resource -> atName(resource, propertyPath.substring(lastSlash + 1)))
                .orElse(ObservedProperty.Absent.INSTANCE);
    }

    private static ObservedProperty atName(Resource resource, String name) {
        final ObservedProperty observed = Optional.ofNullable(resource.adaptTo(Node.class))
                .map(node -> atNode(node, name)).orElse(ObservedProperty.Absent.INSTANCE);
        if (!(observed instanceof ObservedProperty.Absent)) {
            return observed;
        }
        // A provider may expose properties outside its adapted node, including computed values.
        // Use only its current-caller native map; never request a string conversion or new session.
        return Optional.ofNullable(resource.getValueMap().get(name))
                .map(PredicatePropertyReader::nativeValue).orElse(ObservedProperty.Absent.INSTANCE);
    }

    private static ObservedProperty atNode(Node node, String name) {
        try {
            if (!node.hasProperty(name)) {
                return ObservedProperty.Absent.INSTANCE;
            }
            final Property property = node.getProperty(name);
            if (!property.isMultiple()) {
                final Optional<ScalarKind> type = kind(property.getType());
                if (type.isEmpty()) {
                    return ObservedProperty.Unrepresented.INSTANCE;
                }
                return scalar(property.getValue(), type.orElseThrow())
                        .map(value -> (ObservedProperty) new ObservedProperty.Held(
                                new PropertyValue.Single(value))).orElse(ObservedProperty.Absent.INSTANCE);
            }
            final Value[] values = property.getValues();
            if (values.length == 0) {
                return ObservedProperty.EmptyMultiple.INSTANCE;
            }
            final Optional<ScalarKind> type = kind(property.getType());
            if (type.isEmpty()) {
                return ObservedProperty.Unrepresented.INSTANCE;
            }
            final List<PropertyScalar> scalars = Arrays.stream(values)
                    .map(value -> scalar(value, type.orElseThrow())).flatMap(Optional::stream).toList();
            return scalars.size() == values.length
                    ? new ObservedProperty.Held(new PropertyValue.Multiple(scalars))
                    : ObservedProperty.Absent.INSTANCE;
        } catch (final RepositoryException unreadable) {
            return ObservedProperty.Absent.INSTANCE;
        }
    }

    private static Optional<ScalarKind> kind(int type) {
        return switch (type) {
            case PropertyType.STRING -> Optional.of(ScalarKind.STRING);
            case PropertyType.BOOLEAN -> Optional.of(ScalarKind.BOOLEAN);
            case PropertyType.LONG -> Optional.of(ScalarKind.INTEGER);
            case PropertyType.DECIMAL -> Optional.of(ScalarKind.DECIMAL);
            case PropertyType.DATE -> Optional.of(ScalarKind.DATE_TIME);
            case PropertyType.PATH -> Optional.of(ScalarKind.REPOSITORY_PATH);
            default -> Optional.empty();
        };
    }

    private static Optional<PropertyScalar> scalar(Value value, ScalarKind kind) {
        try {
            final String text = switch (kind) {
                case BOOLEAN -> Boolean.toString(value.getBoolean());
                case INTEGER -> Long.toString(value.getLong());
                case DECIMAL -> value.getDecimal().toPlainString();
                case DATE_TIME -> RepositoryValueKind.instantOf(value.getDate().toInstant());
                default -> value.getString();
            };
            return Optional.of(new PropertyScalar(kind, text));
        } catch (final RepositoryException unreadable) {
            // The private value and repository diagnostic never become an exception message or log.
            return Optional.empty();
        }
    }

    private static ObservedProperty nativeValue(Object value) {
        if (value instanceof byte[]) {
            return ObservedProperty.Unrepresented.INSTANCE;
        }
        if (!value.getClass().isArray()) {
            return nativeScalar(value).map(scalar -> (ObservedProperty) new ObservedProperty.Held(
                    new PropertyValue.Single(scalar))).orElse(ObservedProperty.Unrepresented.INSTANCE);
        }
        final int length = Array.getLength(value);
        if (length == 0) {
            return ObservedProperty.EmptyMultiple.INSTANCE;
        }
        final List<PropertyScalar> values = IntStream.range(0, length)
                .mapToObj(position -> Optional.ofNullable(Array.get(value, position))
                        .flatMap(PredicatePropertyReader::nativeScalar))
                .flatMap(Optional::stream).toList();
        if (values.size() != length || values.stream().map(PropertyScalar::kind).distinct().count() != 1) {
            return ObservedProperty.Unrepresented.INSTANCE;
        }
        return new ObservedProperty.Held(new PropertyValue.Multiple(values));
    }

    private static Optional<PropertyScalar> nativeScalar(Object value) {
        return switch (value) {
            case String text -> Optional.of(new PropertyScalar(ScalarKind.STRING, text));
            case Boolean flag -> Optional.of(new PropertyScalar(ScalarKind.BOOLEAN, flag.toString()));
            case Long whole -> integer(whole.longValue());
            case Integer whole -> integer(whole.longValue());
            case Short whole -> integer(whole.longValue());
            case Byte whole -> integer(whole.longValue());
            case BigDecimal decimal -> Optional.of(new PropertyScalar(ScalarKind.DECIMAL,
                    decimal.toPlainString()));
            case Calendar date -> Optional.of(new PropertyScalar(ScalarKind.DATE_TIME,
                    RepositoryValueKind.instantOf(date.toInstant())));
            default -> Optional.empty();
        };
    }

    private static Optional<PropertyScalar> integer(long whole) {
        return Optional.of(new PropertyScalar(ScalarKind.INTEGER, Long.toString(whole)));
    }

}
