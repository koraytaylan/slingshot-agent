// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import rs.slingshot.agent.command.mutation.PropertyValue;

/** A caller-readable property, preserving absence, empty lists, kind and cardinality. */
public sealed interface ObservedProperty permits ObservedProperty.Absent,
        ObservedProperty.EmptyMultiple, ObservedProperty.Unrepresented, ObservedProperty.Held {

    /** No property is readable at the exact relative address. */
    record Absent() implements ObservedProperty {
        /** Reusable observation of absence. */
        public static final Absent INSTANCE = new Absent();
    }

    /** A present repository multivalue containing no elements. */
    record EmptyMultiple() implements ObservedProperty {
        /** Reusable observation of an empty multivalue. */
        public static final EmptyMultiple INSTANCE = new EmptyMultiple();
    }

    /** A present property whose native kind is outside the predicate vocabulary. */
    record Unrepresented() implements ObservedProperty {
        /** Reusable observation without a comparable value. */
        public static final Unrepresented INSTANCE = new Unrepresented();
    }

    /**
     * A present typed value, without conversion between native kinds or cardinalities.
     * @param value the native single value or ordered nonempty list
     */
    record Held(PropertyValue value) implements ObservedProperty {
    }
}
