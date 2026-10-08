// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.stream.IntStream;
import rs.slingshot.agent.command.property.PropertyScalar;

/** The client model's scalar equality and order, without cross-kind conversions. */
final class TypedScalarComparison {

    private TypedScalarComparison() {
    }

    /**
     * Compares scalar values only within the same native kind.
     * @param stored the repository scalar
     * @param asked the predicate operand
     * @return whether the values are equal, including decimal scale equivalence
     */
    static boolean equal(PropertyScalar stored, PropertyScalar asked) {
        if (stored.kind() != asked.kind()) {
            return false;
        }
        if (asked.kind() == rs.slingshot.agent.command.property.ScalarKind.REPOSITORY_PATH) {
            return stored.value().equals(asked.value());
        }
        return order(stored, asked).map(value -> value == 0).orElse(false);
    }

    /**
     * Orders same-kind scalar values as the client model does.
     * @param stored the repository scalar
     * @param asked the predicate operand
     * @return the stored value's order, or absence for unlike kinds, paths, invalid numbers or dates
     */
    static Optional<Integer> order(PropertyScalar stored, PropertyScalar asked) {
        if (stored.kind() != asked.kind()) {
            return Optional.empty();
        }
        try {
            return switch (asked.kind()) {
                case STRING -> Optional.of(text(stored.value(), asked.value()));
                case BOOLEAN -> Optional.of(Boolean.compare(Boolean.parseBoolean(stored.value()),
                        Boolean.parseBoolean(asked.value())));
                case INTEGER, DECIMAL -> Optional.of(asked.compareWith(stored.value()));
                case DATE_TIME -> Optional.of(Instant.parse(stored.value())
                        .compareTo(Instant.parse(asked.value())));
                case REPOSITORY_PATH -> Optional.empty();
            };
        } catch (final NumberFormatException | DateTimeParseException invalid) {
            return Optional.empty();
        }
    }

    /**
     * Identifies repeated membership operands using numeric decimal equality.
     * @param value one membership operand
     * @return the same kind with a scale-independent decimal identity where applicable
     */
    static PropertyScalar equalityKey(PropertyScalar value) {
        if (value.kind() != rs.slingshot.agent.command.property.ScalarKind.DECIMAL) {
            return value;
        }
        try {
            return new PropertyScalar(value.kind(), new BigDecimal(value.value())
                    .stripTrailingZeros().toString());
        } catch (final NumberFormatException invalid) {
            return value;
        }
    }

    private static int text(String stored, String asked) {
        // Rust orders Unicode scalar values; UTF-16 sorts supplementary characters before some
        // BMP characters. Compare the first differing code point without allocating UTF-8 arrays.
        final var different = IntStream.range(0, Math.min(stored.length(), asked.length()))
                .filter(position -> stored.charAt(position) != asked.charAt(position)).findFirst();
        return different.isPresent()
                ? Integer.compare(stored.codePointAt(different.getAsInt()),
                        asked.codePointAt(different.getAsInt()))
                : Integer.compare(stored.length(), asked.length());
    }
}
