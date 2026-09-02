// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes a value in the one form this agent and its client both digest.
 *
 * <p>Four of the client's five identity fields are digests over these bytes. Writing an
 * implementation from a description of a canonicalisation is how two systems end up producing
 * different bytes for the same value and discovering it as a refused submission rather than as a
 * failing vector — so this is proved against the vector file the client is proved against, carried
 * into this repository unchanged.</p>
 *
 * <p>The form itself: members ascend strictly by the bytes of their names at every depth, arrays
 * keep the order they were given, integers are minimal signed base ten, only the quote, the reverse
 * solidus, and the controls are escaped, a control uses a lower-case four-digit scalar, literals are
 * lower-case, and no byte of whitespace appears anywhere.</p>
 */
public final class CanonicalByteWriter {

    /** The form these bytes are in, which is the client's own name for it. */
    public static final String FORMAT = "slingshot.command-canonical-json/1";

    /** The first scalar that needs no escape, so everything below it is a control. */
    private static final int FIRST_UNESCAPED = 0x20;

    /** How wide a control's escape is written. */
    private static final int SCALAR_DIGITS = 4;

    private CanonicalByteWriter() {
    }

    /** The result of writing: the bytes, or the one reason there are none. */
    public sealed interface Outcome permits Written, Refused {
    }

    /**
     * A value the canonical form can carry, and its bytes.
     *
     * @param bytes the canonical bytes
     */
    public record Written(byte[] bytes) implements Outcome {

        /** Holds bytes nothing else can change afterwards. */
        public Written {
            bytes = bytes.clone();
        }

        /**
         * The canonical bytes.
         *
         * @return the bytes, as a copy nothing else holds
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /**
         * The bytes, read as the text they are.
         *
         * @return the rendering
         */
        public String rendered() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * A value the canonical form cannot carry.
     *
     * @param refusal what cannot be written, and where
     */
    public record Refused(CanonicalRefusal refusal) implements Outcome {
    }

    /**
     * Writes one value in the canonical form.
     *
     * @param value the value
     * @return the bytes, or the one reason there are none
     */
    public static Outcome write(DocumentValue value) {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final Optional<CanonicalRefusal> refusal = value(value, "", written);
        return refusal.<Outcome>map(Refused::new)
                .orElseGet(() -> new Written(written.toByteArray()));
    }

    private static Optional<CanonicalRefusal> value(DocumentValue value, String pointer,
                                                    ByteArrayOutputStream written) {
        return switch (value) {
            case DocumentValue.Mapping mapping -> mapping(mapping, pointer, written);
            case DocumentValue.Sequence sequence -> sequence(sequence, pointer, written);
            case DocumentValue.Text text -> text(text.value(), pointer, written,
                    CanonicalRefusal.Failure.NOT_A_WELL_FORMED_STRING);
            case DocumentValue.Whole whole -> literal(Long.toString(whole.value()), written);
            case DocumentValue.Flag flag -> literal(spelled(flag.value()), written);
            case DocumentValue.Nothing ignored -> literal("null", written);
        };
    }

    private static String spelled(DocumentValue.Truth truth) {
        return truth == DocumentValue.Truth.TRUE ? "true" : "false";
    }

    private static Optional<CanonicalRefusal> mapping(DocumentValue.Mapping mapping, String pointer,
                                                      ByteArrayOutputStream written) {
        written.write('{');
        final List<Map.Entry<String, DocumentValue>> ordered = mapping.members().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey()
                        .getBytes(StandardCharsets.UTF_8), java.util.Arrays::compareUnsigned))
                .toList();
        final Optional<CanonicalRefusal> refusal = ordered.stream()
                .flatMap(entry -> member(entry, ordered.indexOf(entry), pointer, written).stream())
                .findFirst();
        written.write('}');
        return refusal;
    }

    private static Optional<CanonicalRefusal> member(Map.Entry<String, DocumentValue> entry,
                                                     int position, String pointer,
                                                     ByteArrayOutputStream written) {
        if (position > 0) {
            written.write(',');
        }
        final String inside = pointer + "/" + entry.getKey();
        final Optional<CanonicalRefusal> named = text(entry.getKey(), inside, written,
                CanonicalRefusal.Failure.NOT_A_WELL_FORMED_NAME);
        if (named.isPresent()) {
            return named;
        }
        written.write(':');
        return value(entry.getValue(), inside, written);
    }

    private static Optional<CanonicalRefusal> sequence(DocumentValue.Sequence sequence,
                                                       String pointer,
                                                       ByteArrayOutputStream written) {
        written.write('[');
        final List<DocumentValue> items = sequence.items();
        final Optional<CanonicalRefusal> refusal = java.util.stream.IntStream
                .range(0, items.size())
                .mapToObj(position -> item(items.get(position), position, pointer, written))
                .flatMap(Optional::stream)
                .findFirst();
        written.write(']');
        return refusal;
    }

    private static Optional<CanonicalRefusal> item(DocumentValue value, int position,
                                                   String pointer,
                                                   ByteArrayOutputStream written) {
        if (position > 0) {
            written.write(',');
        }
        return value(value, pointer + "/" + position, written);
    }

    private static Optional<CanonicalRefusal> literal(String spelling,
                                                      ByteArrayOutputStream written) {
        written.writeBytes(spelling.getBytes(StandardCharsets.UTF_8));
        return Optional.empty();
    }

    private static Optional<CanonicalRefusal> text(String value, String pointer,
                                                   ByteArrayOutputStream written,
                                                   CanonicalRefusal.Failure failure) {
        if (!wellFormed(value)) {
            return Optional.of(new CanonicalRefusal(failure, pointer,
                    "the value carries half of a character, which no byte sequence spells"));
        }
        written.write('"');
        escape(value, written);
        written.write('"');
        return Optional.empty();
    }

    private static void escape(String value, ByteArrayOutputStream written) {
        // By code point rather than by character: a character outside the basic plane is two
        // halves in Java and one character in the bytes, and encoding each half on its own would
        // write two replacements where the sender wrote one character.
        int index = 0;
        while (index < value.length()) {
            final int scalar = value.codePointAt(index);
            written.writeBytes(escaped(scalar));
            index = index + Character.charCount(scalar);
        }
    }

    private static byte[] escaped(int scalar) {
        if (scalar == '"') {
            return "\\\"".getBytes(StandardCharsets.UTF_8);
        }
        if (scalar == '\\') {
            return "\\\\".getBytes(StandardCharsets.UTF_8);
        }
        if (scalar < FIRST_UNESCAPED) {
            return control(scalar);
        }
        return new String(Character.toChars(scalar)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] control(int scalar) {
        final String digits = Integer.toHexString(scalar);
        return ("\\u" + "0".repeat(SCALAR_DIGITS - digits.length()) + digits)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static boolean wellFormed(String value) {
        // Reading by code point pairs the halves that belong together, so a surrogate still
        // standing alone here is one that nothing paired: half of a character, which no byte
        // sequence spells and no writer may emit.
        return value.codePoints().noneMatch(CanonicalByteWriter::isHalfACharacter);
    }

    private static boolean isHalfACharacter(int scalar) {
        return scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE;
    }
}
