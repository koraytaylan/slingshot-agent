// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What one configuration property holds: a type, a cardinality, and one value or several.
 *
 * <p>The type is carried rather than inferred, because a platform configuration is typed and the
 * types are not recoverable from the values. {@code 8080} written back as a string is a
 * configuration that no longer starts a listener, and the failure appears at the next restart
 * rather than at the write — by which time nobody connects the two.</p>
 *
 * <p>The cardinality is carried for the same reason and with a sharper edge: a single-valued
 * property and an array of one are different properties to the service reading them, and a
 * round trip that quietly turned one into the other would be a change nobody asked for reported as
 * a change nobody made.</p>
 *
 * @param type what kind of value this is
 * @param cardinality whether it holds one value or several
 * @param values what it holds, which is exactly one where the cardinality says so
 */
public record ConfigurationValue(String type, Cardinality cardinality, List<String> values) {

    /** The member the type is carried in. */
    public static final String TYPE = "type";

    /** The member the cardinality is carried in. */
    public static final String CARDINALITY = "cardinality";

    /** The member a single value is carried in. */
    public static final String VALUE = "value";

    /** The member several values are carried in. */
    public static final String VALUES = "values";

    /** Whether a property holds one value or several, and if several, how they are held. */
    public enum Cardinality {
        /** One value. */
        SCALAR("scalar"),
        /** Several, held as an array of the primitive type. */
        PRIMITIVE_ARRAY("primitive_array"),
        /** Several, held as an array of the boxed type. */
        SCALAR_ARRAY("scalar_array"),
        /** Several, held as a collection. */
        COLLECTION("collection");

        private final String spelling;

        Cardinality(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How the wire spells this cardinality.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * Whether this cardinality holds exactly one value.
         *
         * @return whether it does
         */
        public boolean isSingle() {
            return this == SCALAR;
        }
    }

    /** Holds a value whose list nothing can change afterwards. */
    public ConfigurationValue {
        values = List.copyOf(values);
    }

    /**
     * What this value holds.
     *
     * @return the values, which nothing may add to
     */
    @Override
    public List<String> values() {
        return values;
    }

    /**
     * This value as it appears in an answer.
     *
     * <p>A single value goes in {@code value} and several in {@code values}, which is the client's
     * own shape rather than a convenience: a reader that always saw a list would have no way to
     * tell a one-element array from a scalar, and those are different configurations.</p>
     *
     * @return the document
     */
    public DocumentValue.Mapping document() {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(TYPE, new DocumentValue.Text(type));
        held.put(CARDINALITY, new DocumentValue.Text(cardinality.spelling()));
        if (cardinality.isSingle()) {
            held.put(VALUE, item(values.isEmpty() ? "" : values.getFirst()));
            return new DocumentValue.Mapping(held);
        }
        held.put(VALUES, new DocumentValue.Sequence(values.stream().map(this::item).toList()));
        return new DocumentValue.Mapping(held);
    }

    /**
     * One item as the client writes it: a boolean as a flag and everything else as text.
     *
     * <p>A number travels as text because the client spells each one exactly — an integer in its
     * one minimal base-ten form, a floating value as the lowercase hexadecimal of its bits — and a
     * number in a document has no exact spelling at all.</p>
     */
    private DocumentValue item(String value) {
        if (BOOLEAN.equals(type)) {
            return new DocumentValue.Flag(Boolean.parseBoolean(value) ? DocumentValue.Truth.TRUE
                    : DocumentValue.Truth.FALSE);
        }
        return new DocumentValue.Text(value);
    }

    /** The type whose values travel as flags rather than as text. */
    private static final String BOOLEAN = "boolean";
}
