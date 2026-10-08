// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/** Reads a selected native text property's identity before provider name decoding can alias it. */
final class ReferenceValues {

    /** The final code point whose native name can be treated as plain ASCII. */
    private static final int MAXIMUM_ASCII_CODE_POINT = 127;

    private ReferenceValues() {
    }

    /**
     * Reads a selected text value, retaining native identity where its name may be translated.
     * @param resource the caller's visible resource
     * @param values the provider values for ordinary names and non-native resources
     * @param name the selected native property name
     * @return the stored value, or absence when no text value remains
     * @throws PersistenceException if the selected native value cannot be read
     */
    static Optional<Object> stored(Resource resource, ValueMap values, String name)
            throws PersistenceException {
        if (!nativeName(name)) {
            return Optional.ofNullable(values.get(name));
        }
        final Node node = resource.adaptTo(Node.class);
        return node == null ? Optional.ofNullable(values.get(name)) : stored(node, values, name);
    }

    /**
     * Reads a selected value through a known native node where its name may be translated.
     * @param node the caller's native node
     * @param values the provider values for ordinary names
     * @param name the selected native property name
     * @return the stored value, or absence when no text value remains
     * @throws PersistenceException if the selected native value cannot be read
     */
    static Optional<Object> stored(Node node, ValueMap values, String name) throws PersistenceException {
        if (!nativeName(name)) {
            return Optional.ofNullable(values.get(name));
        }
        try {
            return stored(node.getProperty(name));
        } catch (final RepositoryException unreadable) {
            throw new PersistenceException("native reference values could not be inspected", unreadable);
        }
    }

    /**
     * Reads a selected value from the native properties already held for assignment.
     * @param nativeProperties native identities held for reads, or empty for legacy provider values
     * @param values the writable provider values where a native identity is not held
     * @param name the selected native property name
     * @return the stored value, or absence when no text value remains
     * @throws PersistenceException if the selected native value cannot be read
     */
    static Optional<Object> stored(Map<String, Property> nativeProperties, ValueMap values, String name)
            throws PersistenceException {
        final Property property = nativeProperties.get(name);
        return property != null ? stored(property) : Optional.ofNullable(values.get(name));
    }

    /**
     * Reads a selected native identity without repeating its parent node's property lookup.
     *
     * <p>The handle is scoped to one complete metadata inspection. Its current type and stored
     * values are still read here; neither metadata nor values are reused on a later visit.</p>
     *
     * @param property the exact native identity selected during this inspection
     * @param values the provider values for ordinary names
     * @param name the exact selected native name
     * @return the current stored value, or absence when no text value remains
     * @throws PersistenceException if the selected native value cannot be read
     */
    static Optional<Object> stored(Property property, ValueMap values, String name)
            throws PersistenceException {
        return nativeName(name) ? stored(property) : Optional.ofNullable(values.get(name));
    }

    /**
     * Reads provider/native values with the whole reference search duration.
     * @param resource the caller-visible resource
     * @param values provider values for ordinary names
     * @param name the exact stored property name
     * @param elapsed the whole search duration
     * @return stored text or absence when its current type is not textual
     * @throws PersistenceException if inspection is incomplete or its deadline elapsed
     */
    static Optional<Object> stored(Resource resource, ValueMap values, String name,
            rs.slingshot.agent.stream.ElapsedTime elapsed) throws PersistenceException {
        requireTime(() -> withinTime(elapsed));
        if (!nativeName(name)) {
            return Optional.ofNullable(values.get(name));
        }
        final Node node = resource.adaptTo(Node.class);
        requireTime(() -> withinTime(elapsed));
        return node == null ? Optional.ofNullable(values.get(name)) : stored(node, values, name, elapsed);
    }

    /**
     * Reads a named native value while preserving the shared search deadline.
     * @param node the caller-visible native node
     * @param values provider values for ordinary names
     * @param name the exact stored property name
     * @param elapsed the whole search duration
     * @return stored text or absence when its current type is not textual
     * @throws PersistenceException if native inspection fails or exceeds its deadline
     */
    static Optional<Object> stored(Node node, ValueMap values, String name,
            rs.slingshot.agent.stream.ElapsedTime elapsed) throws PersistenceException {
        requireTime(() -> withinTime(elapsed));
        if (!nativeName(name)) {
            return Optional.ofNullable(values.get(name));
        }
        try {
            return stored(node.getProperty(name), () -> withinTime(elapsed));
        } catch (final RepositoryException unreadable) {
            throw new PersistenceException("native reference values could not be inspected", unreadable);
        }
    }

    /**
     * Reads one selected identity without giving it a fresh search allowance.
     * @param property the selected native identity
     * @param values provider values for ordinary names
     * @param name the exact selected native name
     * @param elapsed the whole search duration
     * @return stored text or absence when its current type is not textual
     * @throws PersistenceException if native inspection fails or exceeds its deadline
     */
    static Optional<Object> stored(Property property, ValueMap values, String name,
            rs.slingshot.agent.stream.ElapsedTime elapsed) throws PersistenceException {
        requireTime(() -> withinTime(elapsed));
        return nativeName(name) ? stored(property, () -> withinTime(elapsed))
                : Optional.ofNullable(values.get(name));
    }

    private static boolean withinTime(rs.slingshot.agent.stream.ElapsedTime elapsed) {
        return elapsed.milliseconds() <= RepositoryReach.SEARCH_MILLISECONDS;
    }

    private static void requireTime(java.util.function.BooleanSupplier within) throws PersistenceException {
        if (!within.getAsBoolean()) {
            throw new PersistenceException("reference value inspection exceeded the search deadline");
        }
    }

    private static Optional<Object> stored(Property property) throws PersistenceException {
        return stored(property, () -> true);
    }

    private static Optional<Object> stored(Property property, java.util.function.BooleanSupplier within)
            throws PersistenceException {
        try {
            requireTime(within);
            final int kind = property.getType();
            requireTime(within);
            if (!textual(kind)) {
                return Optional.empty();
            }
            final boolean multiple = property.isMultiple();
            requireTime(within);
            if (!multiple) {
                final javax.jcr.Value value = property.getValue();
                requireTime(within);
                final String text = value.getString();
                requireTime(within);
                return Optional.of(text);
            }
            final List<String> strings = new ArrayList<>();
            final javax.jcr.Value[] stored = property.getValues();
            requireTime(within);
            for (final var value : stored) {
                requireTime(within);
                strings.add(value.getString());
                requireTime(within);
            }
            return Optional.of(strings.toArray(String[]::new));
        } catch (final RepositoryException unreadable) {
            throw new PersistenceException("native reference values could not be inspected", unreadable);
        }
    }

    /**
     * Whether provider name translation could lose this native property's identity.
     * @param name the exact stored native property name
     * @return whether native reads and writes must preserve the stored name
     */
    static boolean nativeName(String name) {
        if (name.contains("_x")) {
            return true;
        }
        for (int position = 0; position < name.length(); position = position + 1) {
            if (translatedCharacter(name.charAt(position))) {
                return true;
            }
        }
        return false;
    }

    private static boolean translatedCharacter(int symbol) {
        return symbol > MAXIMUM_ASCII_CODE_POINT || !Character.isLetterOrDigit(symbol)
                && symbol != '_' && symbol != '-' && symbol != '.';
    }

    /**
     * Whether the native kind can hold an exact string reference.
     * @param kind the native property's kind before any stored value is read
     * @return whether this kind can be represented as text
     */
    static boolean textual(int kind) {
        return switch (kind) {
            case PropertyType.BINARY, PropertyType.BOOLEAN, PropertyType.LONG, PropertyType.DOUBLE,
                    PropertyType.DECIMAL, PropertyType.DATE -> false;
            default -> true;
        };
    }
}
