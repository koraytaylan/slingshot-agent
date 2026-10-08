// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/** JCR text-property selection before the provider converts any stored values. */
final class ReferenceProperties {

    /**
     * Text property names and whether all readable metadata was inspected.
     *
     * @param names property names whose provider values can be strings
     * @param completeness whether property metadata could be inspected completely
     */
    record Selection(List<String> names, RepositoryReach.Completeness completeness) {

        /**
         * Whether every property's metadata was readable.
         * @return whether text-property selection is complete
         */
        boolean complete() {
            return completeness == RepositoryReach.Completeness.COMPLETE;
        }
    }

    /**
     * One selected native identity, held only until this inspection reads its current value.
     * @param property the native property returned by metadata enumeration
     * @param name its exact native name before provider decoding
     */
    private record SelectedProperty(Property property, String name) {
    }

    /**
     * Native text properties selected only after the entire metadata pass completes.
     * @param properties native identities whose metadata selected readable text values
     * @param completeness whether every property's metadata could be inspected
     */
    private record NativeSelection(List<SelectedProperty> properties,
                                   RepositoryReach.Completeness completeness) {
    }

    /** Complete metadata is accumulated before any selected value can be read. */
    private static final class MetadataSelection {

        private final List<SelectedProperty> properties = new ArrayList<>();
        private final Set<RepositoryReach.Completeness> failures =
                EnumSet.noneOf(RepositoryReach.Completeness.class);

        private void add(Property property, rs.slingshot.agent.stream.ElapsedTime elapsed) {
            try {
                if (!withinTime(elapsed) || !ReferenceValues.textual(property.getType())
                        || !withinTime(elapsed)) {
                    return;
                }
                final String propertyName = property.getName();
                if (!propertyName.isEmpty()) {
                    properties.add(new SelectedProperty(property, propertyName));
                }
            } catch (final RepositoryException unreadable) {
                failures.add(RepositoryReach.Completeness.INCOMPLETE);
            }
        }

        private NativeSelection result() {
            return new NativeSelection(Collections.unmodifiableList(properties), failures.isEmpty()
                    ? RepositoryReach.Completeness.COMPLETE : RepositoryReach.Completeness.INCOMPLETE);
        }
    }

    private static final String NO_TEXT_PROPERTY = "";

    @FunctionalInterface
    private interface ValueRead {
        Optional<Object> read() throws PersistenceException;
    }

    private ReferenceProperties() {
    }

    /**
     * Selects names without converting a JCR property's stored value.
     * @param resource the caller's visible resource
     * @return selected text names and whether all property metadata was readable
     */
    static Selection select(Resource resource) {
        return Optional.ofNullable(resource.adaptTo(Node.class))
                .map(ReferenceProperties::select)
                .orElseGet(() -> legacySelection(resource));
    }

    private static Selection legacySelection(Resource resource) {
        return legacySelection(resource, rs.slingshot.agent.stream.ElapsedTime.start());
    }

    private static Selection legacySelection(Resource resource,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (!withinTime(elapsed)) {
            return new Selection(List.of(), RepositoryReach.Completeness.INCOMPLETE);
        }
        final ValueMap values = resource.getValueMap();
        if (!withinTime(elapsed)) {
            return new Selection(List.of(), RepositoryReach.Completeness.INCOMPLETE);
        }
        final Set<String> keys = values.keySet();
        if (!withinTime(elapsed)) {
            return new Selection(List.of(), RepositoryReach.Completeness.INCOMPLETE);
        }
        final java.util.Iterator<String> iterator = keys.iterator();
        final List<String> names = Stream.iterate(iterator, held -> withinTime(elapsed)
                        && held.hasNext() && withinTime(elapsed), held -> held)
                .map(java.util.Iterator::next).takeWhile(name -> withinTime(elapsed)).toList();
        return new Selection(names, withinTime(elapsed) ? RepositoryReach.Completeness.COMPLETE
                : RepositoryReach.Completeness.INCOMPLETE);
    }

    private static Selection select(Node node) {
        final NativeSelection selected = nativeSelection(node);
        return new Selection(selected.properties().stream().map(SelectedProperty::name).toList(),
                selected.completeness());
    }

    private static NativeSelection nativeSelection(Node node) {
        return nativeSelection(node, rs.slingshot.agent.stream.ElapsedTime.start());
    }

    private static NativeSelection nativeSelection(Node node,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (!withinTime(elapsed)) {
            return new NativeSelection(List.of(), RepositoryReach.Completeness.INCOMPLETE);
        }
        try {
            final MetadataSelection selected = new MetadataSelection();
            Stream.iterate(node.getProperties(), iterator -> withinTime(elapsed)
                            && iterator.hasNext() && withinTime(elapsed), iterator -> iterator)
                    .map(PropertyIterator::nextProperty)
                    .forEachOrdered(property -> selected.add(property, elapsed));
            return withinTime(elapsed) ? selected.result()
                    : new NativeSelection(List.of(), RepositoryReach.Completeness.INCOMPLETE);
        } catch (final RepositoryException unreadable) {
            return new NativeSelection(List.of(), RepositoryReach.Completeness.INCOMPLETE);
        }
    }

    private static Optional<String> name(Property property, rs.slingshot.agent.stream.ElapsedTime elapsed) {
        try {
            if (!withinTime(elapsed)) {
                return Optional.empty();
            }
            final int kind = property.getType();
            if (!withinTime(elapsed)) {
                return Optional.empty();
            }
            final String name = ReferenceValues.textual(kind) ? property.getName() : NO_TEXT_PROPERTY;
            return withinTime(elapsed) ? Optional.of(name) : Optional.empty();
        } catch (final RepositoryException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Checks exact scalar and multiple text references after complete metadata selection.
     *
     * <p>A native node is held only within this inspection. Stored values are still read
     * individually, and each later inspection adapts its resource again.</p>
     *
     * @param resource the caller's visible resource
     * @param address the exact reference sought
     * @return whether a text value mentions the address, or absence on unreadable metadata
     */
    static Optional<Boolean> mentions(Resource resource, String address) {
        return mentions(resource, address, rs.slingshot.agent.stream.ElapsedTime.start());
    }

    /**
     * Inspects exact references without starting a new per-resource time allowance.
     * @param resource the caller-visible resource
     * @param address the exact reference sought
     * @param elapsed the whole search duration
     * @return a match, complete absence, or absence on incomplete inspection
     */
    static Optional<Boolean> mentions(Resource resource, String address,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        final Node nativeNode = resource.adaptTo(Node.class);
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        if (nativeNode != null) {
            return mentions(nativeNode, resource, address, elapsed);
        }
        final Selection selection = legacySelection(resource, elapsed);
        if (!selection.complete() || !withinTime(elapsed)) {
            return Optional.empty();
        }
        final ValueMap values = resource.getValueMap();
        return selection.names().stream()
                .map(name -> mention(() -> ReferenceValues.stored(resource, values, name, elapsed), address,
                        elapsed))
                .filter(held -> held.isEmpty() || held.orElseThrow())
                .findFirst().orElseGet(() -> withinTime(elapsed) ? Optional.of(false) : Optional.empty());
    }

    /**
     * Checks native properties until a reference or unreadable metadata decides deletion refusal.
     * @param resource the caller's visible resource
     * @param address the exact reference sought
     * @return a known match, complete absence, or absence on unreadable preceding metadata
     */
    static Optional<Boolean> firstMention(Resource resource, String address) {
        return firstMention(resource, address, rs.slingshot.agent.stream.ElapsedTime.start());
    }

    /**
     * Stops decisive deletion inspection at the same timer as its surrounding walk.
     * @param resource the caller-visible resource
     * @param address the exact reference sought
     * @param elapsed the whole search duration
     * @return a decisive match, complete absence, or incomplete inspection
     */
    static Optional<Boolean> firstMention(Resource resource, String address,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        final Node node = resource.adaptTo(Node.class);
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        return node == null ? mentions(resource, address, elapsed)
                : firstMention(node, resource.getValueMap(), address, elapsed);
    }

    private static Optional<Boolean> mentions(Node node, Resource resource, String address,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        final NativeSelection selection = nativeSelection(node, elapsed);
        if (selection.completeness() != RepositoryReach.Completeness.COMPLETE) {
            return Optional.empty();
        }
        final ValueMap values = resource.getValueMap();
        return selection.properties().stream()
                .map(property -> mention(() -> ReferenceValues.stored(
                        property.property(), values, property.name(), elapsed), address, elapsed))
                .filter(held -> held.isEmpty() || held.orElseThrow())
                .findFirst().orElseGet(() -> withinTime(elapsed) ? Optional.of(false) : Optional.empty());
    }

    private static Optional<Boolean> firstMention(Node node, ValueMap values, String address,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        try {
            return Stream.iterate(node.getProperties(), iterator -> withinTime(elapsed)
                            && iterator.hasNext() && withinTime(elapsed), iterator -> iterator)
                    .map(PropertyIterator::nextProperty)
                    .map(property -> withinTime(elapsed) ? name(property, elapsed) : Optional.<String>empty())
                    .map(name -> name.flatMap(property ->
                            firstPropertyMention(node, values, property, address, elapsed)))
                    .filter(mention -> mention.isEmpty() || mention.orElseThrow())
                    .findFirst().orElseGet(() -> withinTime(elapsed) ? Optional.of(false) : Optional.empty());
        } catch (final RepositoryException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> firstPropertyMention(Node node, ValueMap values,
                                                         String name, String address,
                                                                 rs.slingshot.agent.stream.ElapsedTime
                                                                 elapsed) {
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        if (name.isEmpty()) {
            return Optional.of(false);
        }
        return mention(() -> ReferenceValues.stored(node, values, name, elapsed), address, elapsed);
    }

    private static Optional<Boolean> mention(ValueRead read, String address,
            rs.slingshot.agent.stream.ElapsedTime elapsed) {
        if (!withinTime(elapsed)) {
            return Optional.empty();
        }
        try {
            final Optional<Object> stored = read.read();
            if (!withinTime(elapsed)) {
                return Optional.empty();
            }
            final boolean matched = stored.map(value -> matches(value, address)).orElse(false);
            return withinTime(elapsed) ? Optional.of(matched) : Optional.empty();
        } catch (final PersistenceException unreadable) {
            return Optional.empty();
        }
    }

    private static boolean withinTime(rs.slingshot.agent.stream.ElapsedTime elapsed) {
        return elapsed.milliseconds() <= RepositoryReach.SEARCH_MILLISECONDS;
    }

    private static boolean matches(Object value, String address) {
        return value instanceof final String text && text.equals(address)
                || value instanceof final String[] several && Arrays.asList(several).contains(address);
    }
}
