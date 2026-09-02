// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * One value in a document, as one of the six things a value can be.
 *
 * <p>The set is closed, so a caller that handles every case cannot be surprised by a seventh, and
 * the compiler is what says so. Non-integral numbers are absent on purpose: the canonical form this
 * agent and its client agree on forbids them, and a value model that could hold one would be a
 * model that can hold a document nothing can write back.</p>
 */
public sealed interface DocumentValue
        permits DocumentValue.Mapping, DocumentValue.Sequence, DocumentValue.Text,
                DocumentValue.Whole, DocumentValue.Flag, DocumentValue.Nothing {

    /**
     * An object: named members, in the order they were read.
     *
     * @param members the members, by name
     */
    record Mapping(SequencedMap<String, DocumentValue> members) implements DocumentValue {

        /** Holds members in the order they arrived, which nothing else can change afterwards. */
        public Mapping {
            members = new LinkedHashMap<>(members);
        }

        /**
         * The members, by name, in the order they were read.
         *
         * @return the members, as a view nothing can change
         */
        @Override
        public SequencedMap<String, DocumentValue> members() {
            return java.util.Collections.unmodifiableSequencedMap(members);
        }

        /**
         * The value one member carries.
         *
         * @param name the member's name
         * @return the value, or nothing where this object carries no such member
         */
        public Optional<DocumentValue> member(String name) {
            return Optional.ofNullable(members.get(name));
        }
    }

    /**
     * An array: values in the order they were read.
     *
     * @param items the values
     */
    record Sequence(List<DocumentValue> items) implements DocumentValue {

        /** Holds items nothing can change afterwards. */
        public Sequence {
            items = List.copyOf(items);
        }
    }

    /**
     * A string.
     *
     * @param value the string itself, already decoded
     */
    record Text(String value) implements DocumentValue {
    }

    /**
     * A whole number, which is the only kind of number a document may carry.
     *
     * @param value the number
     */
    record Whole(long value) implements DocumentValue {
    }

    /** The two values a boolean can be, named so a call site states which one it means. */
    enum Truth {
        /** The true literal. */
        TRUE,
        /** The false literal. */
        FALSE
    }

    /**
     * A boolean.
     *
     * @param value which of the two it is
     */
    record Flag(Truth value) implements DocumentValue {
    }

    /** The null literal, which is a value rather than the absence of one. */
    record Nothing() implements DocumentValue {
    }
}
