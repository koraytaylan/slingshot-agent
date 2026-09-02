// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rs.slingshot.agent.command.property.PropertyScalar;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one property is being set to: one value, or a list of them.
 *
 * <p>The cardinality is stated rather than read off the shape of what was sent. A repository
 * distinguishes a property holding one value from a property holding a list of one, and a caller
 * who writes the second where the first was meant produces content that reads back differently
 * through every tool that looks at it. So the caller says which, and a list of one stays a list.</p>
 */
public sealed interface PropertyValue permits PropertyValue.Single, PropertyValue.Multiple {

    /** The member saying which of the two shapes a value has. */
    String CARDINALITY = "cardinality";

    /** How the shape holding one value is spelled. */
    String SINGLE = "single";

    /** How the shape holding a list is spelled. */
    String MULTIPLE = "multiple";

    /** The member a single value is carried in. */
    String VALUE = "value";

    /** The member a list of values is carried in. */
    String VALUES = "values";

    /**
     * Every member a property value's own document has, the scalar's included.
     *
     * <p>Borrowed by the commands that set properties rather than restated by each: eight of them
     * carry this document, and eight copies of these names is eight places for them to drift.</p>
     */
    List<String> MEMBERS =
            List.of(CARDINALITY, PropertyScalar.TYPE, VALUE, PropertyScalar.VALUE, VALUES);

    /**
     * What is being written, whichever shape it has.
     *
     * @return the values, which is exactly one for the single shape
     */
    List<PropertyScalar> values();

    /**
     * This value as the repository takes it.
     *
     * <p>A list stays a list, including a list of one. The repository tells a property holding one
     * value from one holding a list of one, and so does every tool that reads the node afterwards —
     * so the shape the caller stated is the shape that gets written.</p>
     *
     * @return what to hand the repository
     */
    default Object stored() {
        return this instanceof Single
                ? values().getFirst().value()
                : values().stream()
                        .map(PropertyScalar::value)
                        .toArray(String[]::new);
    }

    /**
     * A property holding one value.
     *
     * @param scalar the value
     */
    record Single(PropertyScalar scalar) implements PropertyValue {

        @Override
        public List<PropertyScalar> values() {
            return List.of(scalar);
        }
    }

    /**
     * A property holding a list of them.
     *
     * @param held the values, of which there is at least one
     */
    record Multiple(List<PropertyScalar> held) implements PropertyValue {

        /** Holds the values apart from whatever produced them. */
        public Multiple {
            held = List.copyOf(held);
        }

        @Override
        public List<PropertyScalar> values() {
            return Collections.unmodifiableList(held);
        }
    }

    /** Why a value is not one this contract writes. */
    enum Refusal {
        /** The value is not an object. */
        NOT_A_DOCUMENT,
        /** The cardinality is neither of the two there are. */
        UNKNOWN_CARDINALITY,
        /** The value carries what its cardinality does not take, or omits what it does. */
        FIELDS_DO_NOT_MATCH_CARDINALITY,
        /** A list of values is empty, which is not a list and not an absence either. */
        VALUES_EMPTY,
        /** A list of values is longer than the contract allows. */
        VALUES_TOO_MANY,
        /** A scalar is not one this contract holds. */
        SCALAR_REJECTED
    }

    /** The result of reading one: the value, or the one reason there is none. */
    sealed interface Outcome permits Held, Refused {
    }

    /**
     * A value this contract writes.
     *
     * @param value the value
     */
    record Held(PropertyValue value) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one written value.
     *
     * @param written the value as the caller wrote it
     * @param contract the authenticated contract, which bounds a list's length
     * @return the value, or the one reason there is none
     */
    static Outcome of(DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "a property value says how many values it holds and then holds them");
        }
        if (!(mapping.member(CARDINALITY).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text cardinality)) {
            return new Refused(Refusal.UNKNOWN_CARDINALITY,
                    CARDINALITY + " says whether this property holds one value or a list");
        }
        return switch (cardinality.value()) {
            case SINGLE -> single(mapping);
            case MULTIPLE -> multiple(mapping, contract);
            default -> new Refused(Refusal.UNKNOWN_CARDINALITY, cardinality.value() + " is neither "
                    + SINGLE + " nor " + MULTIPLE);
        };
    }

    private static Outcome single(DocumentValue.Mapping mapping) {
        if (mapping.member(VALUES).isPresent() || mapping.member(VALUE).isEmpty()) {
            return new Refused(Refusal.FIELDS_DO_NOT_MATCH_CARDINALITY,
                    SINGLE + " holds one value, in " + VALUE);
        }
        final PropertyScalar.Outcome read = PropertyScalar.of(mapping.member(VALUE).orElseThrow());
        return read instanceof final PropertyScalar.Refused refused
                ? new Refused(Refusal.SCALAR_REJECTED, refused.detail())
                : new Held(new Single(((PropertyScalar.Held) read).scalar()));
    }

    private static Outcome multiple(DocumentValue.Mapping mapping, AgentContract contract) {
        if (mapping.member(VALUE).isPresent() || mapping.member(VALUES).isEmpty()) {
            return new Refused(Refusal.FIELDS_DO_NOT_MATCH_CARDINALITY,
                    MULTIPLE + " holds a list of values, in " + VALUES);
        }
        if (!(mapping.member(VALUES).orElseThrow() instanceof final DocumentValue.Sequence items)) {
            return new Refused(Refusal.FIELDS_DO_NOT_MATCH_CARDINALITY,
                    VALUES + " is a list of typed values");
        }
        if (items.items().isEmpty()) {
            return new Refused(Refusal.VALUES_EMPTY, MULTIPLE + " holds at least one value; a"
                    + " property with none is a property removed, which is said in its own list");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PROPERTY_VALUE_ITEMS);
        if (items.items().size() > bound) {
            return new Refused(Refusal.VALUES_TOO_MANY, items.items().size() + " values is more"
                    + " than the " + bound + " one property holds");
        }
        final List<PropertyScalar> held = new ArrayList<>();
        for (final DocumentValue item : items.items()) {
            final PropertyScalar.Outcome read = PropertyScalar.of(item);
            if (read instanceof final PropertyScalar.Refused refused) {
                return new Refused(Refusal.SCALAR_REJECTED, refused.detail());
            }
            held.add(((PropertyScalar.Held) read).scalar());
        }
        return new Held(new Multiple(held));
    }
}
