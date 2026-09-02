// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.property;

import rs.slingshot.agent.json.DocumentValue;

/**
 * One typed value a search compares a property against.
 *
 * <p>The kind travels with the value rather than being read off the value's shape. Two values of
 * unlike kinds never compare, so a caller who wrote a number where a string was stored is refused
 * rather than told there are no matches.</p>
 *
 * @param kind what kind of value this is
 * @param value the value as it was written, which is text for every kind including the flags
 */
public record PropertyScalar(ScalarKind kind, String value) {

    /** The member a value's own kind is carried in. */
    public static final String TYPE = "type";

    /** The member a value's own text is carried in. */
    public static final String VALUE = "value";

    /** Why a value is not one this vocabulary holds. */
    public enum Refusal {
        /** The value is not an object. */
        NOT_A_DOCUMENT,
        /** The kind is not one of the six. */
        UNKNOWN_KIND,
        /** The value is neither text nor a flag. */
        VALUE_NOT_A_SCALAR
    }

    /** The result of reading one: the value, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * A value this vocabulary holds.
     *
     * @param scalar the value
     */
    public record Held(PropertyScalar scalar) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one written value.
     *
     * @param written the value as the caller wrote it
     * @return the value, or the one reason there is none
     */
    public static Outcome of(DocumentValue written) {
        if (!(written instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "a value carries its own kind beside it");
        }
        if (!(mapping.member(TYPE).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text spelled)) {
            return new Refused(Refusal.UNKNOWN_KIND, TYPE + " names what kind of value this is");
        }
        final java.util.Optional<ScalarKind> kind = ScalarKind.named(spelled.value());
        if (kind.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_KIND,
                    spelled.value() + " is not a kind of value this contract compares");
        }
        return valued(kind.orElseThrow(), mapping);
    }

    private static Outcome valued(ScalarKind kind, DocumentValue.Mapping mapping) {
        return switch (mapping.member(VALUE).orElse(new DocumentValue.Nothing())) {
            case DocumentValue.Text text -> new Held(new PropertyScalar(kind, text.value()));
            case DocumentValue.Flag flag -> new Held(new PropertyScalar(kind,
                    String.valueOf(flag.value() == DocumentValue.Truth.TRUE)));
            default -> new Refused(Refusal.VALUE_NOT_A_SCALAR,
                    VALUE + " is text or a flag; a value of any other shape has no kind here");
        };
    }

    /**
     * How this value compares with what the repository holds under a property.
     *
     * <p>The repository's own value is rendered to text by the same rule for every kind, and the
     * comparison is then between two strings — except for the ordered kinds, where numbers are
     * compared as numbers so that ten does not sort before nine.</p>
     *
     * @param stored what the repository holds, as it renders it
     * @return negative where the stored value sorts first, zero where they are equal, positive
     *     where this one sorts first
     */
    public int compareWith(String stored) {
        return switch (kind) {
            case INTEGER -> Long.compare(wholeOf(stored), wholeOf(value));
            case DECIMAL -> new java.math.BigDecimal(digitsOf(stored))
                    .compareTo(new java.math.BigDecimal(digitsOf(value)));
            default -> stored.compareTo(value);
        };
    }

    private static long wholeOf(String written) {
        try {
            return Long.parseLong(written);
        } catch (final NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String digitsOf(String written) {
        try {
            return new java.math.BigDecimal(written).toPlainString();
        } catch (final NumberFormatException notANumber) {
            return "0";
        }
    }
}
