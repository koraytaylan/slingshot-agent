// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What an update does to a node's properties: what to set, and what to take away.
 *
 * <p>Two lists, and a property in neither is left exactly as it was. That is the only arrangement
 * without a trap in it. An update that treated an absent property as a removal would make a caller
 * who sent a partial view destroy the rest of the node; one that treated an empty value as a
 * removal would make an intentionally empty title impossible to write. Two lists cost one extra
 * member and remove the whole question.</p>
 *
 * <p>A name in both lists is refused rather than resolved. Set-then-remove and remove-then-set are
 * both defensible readings and they disagree, so there is no answer to give — and a caller who
 * wrote both meant one of them, which they can say.</p>
 *
 * @param set the properties to write, by name, in the order they were written
 * @param removed the properties to take away, by name
 */
public record PropertyChange(SequencedMap<String, PropertyValue> set,
                             SequencedSet<String> removed) {

    /** The member the properties to write are carried in. */
    public static final String PROPERTIES = "properties";

    /** The member the names to take away are carried in. */
    public static final String REMOVED_PROPERTY_NAMES = "removed_property_names";

    /**
     * Every member a change's own document has, the value documents inside it included.
     *
     * <p>Naming a value's own member brings the whole value document with it, which is how the
     * eight commands that carry a change declare what a schema declares without restating it.</p>
     */
    public static final List<String> MEMBERS =
            List.of(PROPERTIES, PropertyValue.CARDINALITY, REMOVED_PROPERTY_NAMES);

    /** An update that names neither list, which changes nothing about a node's properties. */
    public static final PropertyChange NOTHING =
            new PropertyChange(new LinkedHashMap<>(), new LinkedHashSet<>());

    /** Holds both lists apart from whatever the caller still has a reference to. */
    public PropertyChange {
        set = new LinkedHashMap<>(set);
        removed = new LinkedHashSet<>(removed);
    }

    /**
     * The properties this change writes.
     *
     * @return them by name, which nothing may add to
     */
    @Override
    public SequencedMap<String, PropertyValue> set() {
        return Collections.unmodifiableSequencedMap(set);
    }

    /**
     * The properties this change takes away.
     *
     * @return their names, which nothing may add to
     */
    @Override
    public SequencedSet<String> removed() {
        return Collections.unmodifiableSequencedSet(removed);
    }

    /**
     * Whether this change would leave a node's properties exactly as they were.
     *
     * @return whether it would, which a handler reports rather than committing nothing
     */
    public boolean isEmpty() {
        return set.isEmpty() && removed.isEmpty();
    }

    /** Why a change is not one this contract makes. */
    public enum Refusal {
        /** The properties are not a document of them. */
        PROPERTIES_NOT_A_DOCUMENT,
        /** The removed names are not a list of names. */
        REMOVALS_NOT_A_LIST,
        /** More properties were named than the contract allows. */
        TOO_MANY_PROPERTIES,
        /** More removals were named than the contract allows. */
        TOO_MANY_REMOVALS,
        /** A property's name is longer than the contract allows. */
        NAME_TOO_LONG,
        /** A property's value is not one this contract writes. */
        VALUE_REJECTED,
        /** One name is in both lists, and the two readings of that disagree. */
        NAME_IN_BOTH_LISTS
    }

    /**
     * The first property this change asks to remove that a node will not release, where there is
     * one.
     *
     * <p>Asked by removing and then looking, rather than by consulting a list of protected names. A
     * list here would be this build's guess at what a particular repository protects, and
     * repositories differ — an automatically maintained property on one is an ordinary property on
     * another. What does not differ is that the repository itself knows, and it answers by still
     * having the property afterwards.</p>
     *
     * <p>The removals are staged and not committed, so a refusal from here leaves the repository as
     * it was: the session is discarded with the request.</p>
     *
     * @param values the node's own properties
     * @return the first that would not go, or nothing where they all would
     */
    public Optional<String> immovableIn(java.util.Map<String, Object> values) {
        return removed.stream()
                .filter(name -> {
                    values.remove(name);
                    return values.containsKey(name);
                })
                .findFirst();
    }

    /** The result of reading one: the change, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A change this contract makes.
     *
     * @param change what was asked
     */
    public record Held(PropertyChange change) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names the property rather than its value
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads the change one argument names, which is nothing where it names neither list.
     *
     * @param arguments the whole argument document
     * @param contract the authenticated contract, which bounds both lists and every name
     * @return the change, or the one reason there is none
     */
    public static Outcome of(DocumentValue.Mapping arguments, AgentContract contract) {
        final Outcome written = written(arguments, contract);
        if (written instanceof Refused) {
            return written;
        }
        final Outcome removals = removals(arguments, contract);
        if (removals instanceof Refused) {
            return removals;
        }
        final SequencedMap<String, PropertyValue> set = ((Held) written).change().set();
        final SequencedSet<String> removed = ((Held) removals).change().removed();
        final Optional<String> both = set.keySet().stream()
                .filter(removed::contains)
                .findFirst();
        if (both.isPresent()) {
            return new Refused(Refusal.NAME_IN_BOTH_LISTS, both.get() + " is named as a property to"
                    + " write and as one to take away. Writing then removing and removing then"
                    + " writing are different outcomes, so there is nothing to do that is not a"
                    + " guess at which was meant.");
        }
        return new Held(new PropertyChange(set, removed));
    }

    private static Outcome written(DocumentValue.Mapping arguments, AgentContract contract) {
        final Optional<DocumentValue> asked = arguments.member(PROPERTIES);
        if (asked.isEmpty()) {
            return new Held(NOTHING);
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Mapping properties)) {
            return new Refused(Refusal.PROPERTIES_NOT_A_DOCUMENT,
                    PROPERTIES + " names properties by name, each with what to write");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_MUTATION_PROPERTIES);
        if (properties.members().size() > bound) {
            return new Refused(Refusal.TOO_MANY_PROPERTIES, properties.members().size()
                    + " properties is more than the " + bound + " one change writes");
        }
        final SequencedMap<String, PropertyValue> set = new LinkedHashMap<>();
        for (final var property : properties.members().entrySet()) {
            final Outcome named = named(property.getKey(), contract);
            if (named instanceof Refused) {
                return named;
            }
            final PropertyValue.Outcome value = PropertyValue.of(property.getValue(), contract);
            if (value instanceof final PropertyValue.Refused refused) {
                return new Refused(Refusal.VALUE_REJECTED,
                        property.getKey() + ": " + refused.detail());
            }
            set.put(property.getKey(), ((PropertyValue.Held) value).value());
        }
        return new Held(new PropertyChange(set, new LinkedHashSet<>()));
    }

    private static Outcome removals(DocumentValue.Mapping arguments, AgentContract contract) {
        final Optional<DocumentValue> asked = arguments.member(REMOVED_PROPERTY_NAMES);
        if (asked.isEmpty()) {
            return new Held(NOTHING);
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence names)) {
            return new Refused(Refusal.REMOVALS_NOT_A_LIST,
                    REMOVED_PROPERTY_NAMES + " is a list of property names");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_REMOVED_PROPERTY_NAMES);
        if (names.items().size() > bound) {
            return new Refused(Refusal.TOO_MANY_REMOVALS, names.items().size() + " removals is more"
                    + " than the " + bound + " one change takes away");
        }
        final SequencedSet<String> removed = new LinkedHashSet<>();
        for (final DocumentValue item : names.items()) {
            if (!(item instanceof final DocumentValue.Text name)) {
                return new Refused(Refusal.REMOVALS_NOT_A_LIST,
                        REMOVED_PROPERTY_NAMES + " holds something that is not a property name");
            }
            final Outcome named = named(name.value(), contract);
            if (named instanceof Refused) {
                return named;
            }
            removed.add(name.value());
        }
        return new Held(new PropertyChange(new LinkedHashMap<>(), removed));
    }

    private static Outcome named(String name, AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_PROPERTY_NAME_BYTES);
        return name.length() > bound
                ? new Refused(Refusal.NAME_TOO_LONG,
                        name.length() + " characters is longer than the " + bound
                                + " a property's name may be")
                : new Held(NOTHING);
    }

}
