// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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

    /** Every member one value document has, and there is no fifth. */
    public static final List<String> MEMBERS = List.of(CARDINALITY, TYPE, VALUE, VALUES);

    /** The types a configuration property may have, which the client publishes as a closed set. */
    public static final List<String> TYPES = List.of("string", "boolean", "character", "byte",
            "short", "integer", "long", "float", "double");

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

        /**
         * The cardinality one spelling names.
         *
         * @param spelled what was written
         * @return the cardinality, or nothing where nothing is spelled that way
         */
        public static Optional<Cardinality> named(String spelled) {
            return Arrays.stream(values())
                    .filter(held -> held.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Every cardinality, spelled as the wire spells it.
         *
         * @return the spellings, in declaration order
         */
        public static List<String> spellings() {
            return Arrays.stream(values()).map(Cardinality::spelling).toList();
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
            held.put(VALUE, new DocumentValue.Text(values.isEmpty() ? "" : values.getFirst()));
            return new DocumentValue.Mapping(held);
        }
        held.put(VALUES, new DocumentValue.Sequence(values.stream()
                .map(value -> (DocumentValue) new DocumentValue.Text(value))
                .toList()));
        return new DocumentValue.Mapping(held);
    }
}
