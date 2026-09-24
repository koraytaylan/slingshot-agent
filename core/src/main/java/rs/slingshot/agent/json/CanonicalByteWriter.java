// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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

    /** The first scalar whose UTF-8 form is two bytes. */
    private static final int UTF8_TWO_BYTES = 0x80;

    /** The first scalar whose UTF-8 form is three bytes. */
    private static final int UTF8_THREE_BYTES = 0x800;

    /** The first scalar whose UTF-8 form is four bytes. */
    private static final int UTF8_FOUR_BYTES = 0x10000;

    /** The lead byte of a two-byte UTF-8 sequence. */
    private static final int UTF8_TWO_BYTE_LEAD = 0xC0;

    /** The lead byte of a three-byte UTF-8 sequence. */
    private static final int UTF8_THREE_BYTE_LEAD = 0xE0;

    /** The lead byte of a four-byte UTF-8 sequence. */
    private static final int UTF8_FOUR_BYTE_LEAD = 0xF0;

    /** How far a UTF-8 payload is shifted to reach the next byte. */
    private static final int UTF8_BYTE_SHIFT = 6;

    /** How far a three-byte scalar is shifted to reach its lead byte. */
    private static final int UTF8_THREE_BYTE_SHIFT = 12;

    /** How far a four-byte scalar is shifted to reach its lead byte. */
    private static final int UTF8_FOUR_BYTE_SHIFT = 18;

    /** The bits of a UTF-8 continuation byte that carry the scalar. */
    private static final int UTF8_CONTINUATION_BITS = 0x3F;

    /** The mark of a UTF-8 continuation byte. */
    private static final int UTF8_CONTINUATION_MARK = 0x80;

    /** The bits of one hexadecimal digit. */
    private static final int HEXADECIMAL_DIGIT_BITS = 0xF;

    /** Where the letters of a hexadecimal digit start, after the ten digits. */
    private static final int HEXADECIMAL_LETTER_OFFSET = 10;

    /** How far each successive hexadecimal digit of a control is shifted. */
    private static final int HEXADECIMAL_DIGIT_SHIFT = 4;

    /** The shift of the first hexadecimal digit of a four-digit control. */
    private static final int HEXADECIMAL_FIRST_DIGIT_SHIFT = 12;

    /** The shift of the second hexadecimal digit of a four-digit control. */
    private static final int HEXADECIMAL_SECOND_DIGIT_SHIFT = 8;

    private CanonicalByteWriter() {
    }

    /** One member, with the bytes its name orders by, computed once. */
    private static final class OrderedMember {

        private final byte[] keyBytes;

        private final Map.Entry<String, DocumentValue> entry;

        private OrderedMember(Map.Entry<String, DocumentValue> entry) {
            this.keyBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            this.entry = entry;
        }
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
        final List<OrderedMember> ordered = mapping.members().entrySet().stream()
                .map(OrderedMember::new)
                .sorted((left, right) -> java.util.Arrays.compareUnsigned(left.keyBytes,
                        right.keyBytes))
                .toList();
        final Optional<CanonicalRefusal> refusal = java.util.stream.IntStream
                .range(0, ordered.size())
                .mapToObj(position -> member(ordered.get(position).entry, position, pointer, written))
                .flatMap(Optional::stream)
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
        written.write('"');
        final Optional<CanonicalRefusal> refusal = escape(value, pointer, written, failure);
        if (refusal.isPresent()) {
            return refusal;
        }
        written.write('"');
        return Optional.empty();
    }

    // By code point rather than by character: a character outside the basic plane is two halves
    // in Java and one character in the bytes, and encoding each half on its own would write two
    // replacements where the sender wrote one character. A surrogate still standing alone is one
    // that nothing paired: half of a character, which no byte sequence spells.
    private static Optional<CanonicalRefusal> escape(String value, String pointer,
                                                     ByteArrayOutputStream written,
                                                     CanonicalRefusal.Failure failure) {
        for (int index = 0; index < value.length(); ) {
            final int scalar = value.codePointAt(index);
            if (isHalfACharacter(scalar)) {
                return Optional.of(new CanonicalRefusal(failure, pointer,
                        "the value carries half of a character, which no byte sequence spells"));
            }
            writeScalar(scalar, written);
            index = index + Character.charCount(scalar);
        }
        return Optional.empty();
    }

    private static void writeScalar(int scalar, ByteArrayOutputStream written) {
        if (scalar == '"') {
            written.write('\\');
            written.write('"');
            return;
        }
        if (scalar == '\\') {
            written.write('\\');
            written.write('\\');
            return;
        }
        if (scalar < FIRST_UNESCAPED) {
            writeControl(scalar, written);
            return;
        }
        if (scalar < UTF8_TWO_BYTES) {
            written.write(scalar);
            return;
        }
        if (scalar < UTF8_THREE_BYTES) {
            written.write(UTF8_TWO_BYTE_LEAD | (scalar >> UTF8_BYTE_SHIFT));
            written.write(UTF8_CONTINUATION_MARK | (scalar & UTF8_CONTINUATION_BITS));
            return;
        }
        if (scalar < UTF8_FOUR_BYTES) {
            written.write(UTF8_THREE_BYTE_LEAD | (scalar >> UTF8_THREE_BYTE_SHIFT));
            written.write(UTF8_CONTINUATION_MARK
                    | ((scalar >> UTF8_BYTE_SHIFT) & UTF8_CONTINUATION_BITS));
            written.write(UTF8_CONTINUATION_MARK | (scalar & UTF8_CONTINUATION_BITS));
            return;
        }
        written.write(UTF8_FOUR_BYTE_LEAD | (scalar >> UTF8_FOUR_BYTE_SHIFT));
        written.write(UTF8_CONTINUATION_MARK
                | ((scalar >> UTF8_THREE_BYTE_SHIFT) & UTF8_CONTINUATION_BITS));
        written.write(UTF8_CONTINUATION_MARK
                | ((scalar >> UTF8_BYTE_SHIFT) & UTF8_CONTINUATION_BITS));
        written.write(UTF8_CONTINUATION_MARK | (scalar & UTF8_CONTINUATION_BITS));
    }

    private static void writeControl(int scalar, ByteArrayOutputStream written) {
        written.write('\\');
        written.write('u');
        written.write(hex(scalar >> HEXADECIMAL_FIRST_DIGIT_SHIFT));
        written.write(hex(scalar >> HEXADECIMAL_SECOND_DIGIT_SHIFT));
        written.write(hex(scalar >> HEXADECIMAL_DIGIT_SHIFT));
        written.write(hex(scalar));
    }

    private static int hex(int nibble) {
        final int value = nibble & HEXADECIMAL_DIGIT_BITS;
        return value < HEXADECIMAL_LETTER_OFFSET
                ? '0' + value
                : 'a' + value - HEXADECIMAL_LETTER_OFFSET;
    }

    private static boolean isHalfACharacter(int scalar) {
        return scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE;
    }
}
