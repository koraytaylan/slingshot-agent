// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicLong;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/**
 * Reads one document, under bounds enforced as the bytes arrive.
 *
 * <p>The bound is applied to the next byte rather than to the document, because a document that has
 * already been collected has already cost what the bound was there to stop it costing. A refusal
 * therefore happens where it happens, names the bound and the position, and carries nothing that
 * was read — there is no path on which a caller holds part of a document this reader refused.</p>
 *
 * <p>Duplicate members are refused outright rather than resolved. Two implementations that each
 * pick a winner will eventually pick differently, and every digest taken over the result would then
 * differ for a document both of them accepted.</p>
 */
public final class BoundedDocumentReader {

    /** What a stream answers when it has no more bytes. */
    private static final int END_OF_INPUT = -1;

    // A parse has exactly two pieces of state: how many bytes have been consumed, and the one byte
    // that was read to find out a value had ended. Both are held by objects that own their own
    // mutation, so this class holds none - which is what lets a reader be read as a description of
    // the grammar rather than as a description of a machine.
    private final PushbackInputStream stream;
    private final Bounds bounds;
    private final long declaredLength;
    private final AtomicLong consumed = new AtomicLong();

    private BoundedDocumentReader(InputStream stream, long declaredLength, Bounds bounds) {
        this.stream = new PushbackInputStream(stream);
        this.declaredLength = declaredLength;
        this.bounds = bounds;
    }

    /**
     * The four bounds a document is read under.
     *
     * <p>They are a value rather than four arguments so that the one place they are read from is
     * {@link #from(AgentContract)}, and so a suite proving what a bound does can state a smaller one
     * without this package ever writing a number down.</p>
     *
     * @param documentBytes how long the whole document may be
     * @param nestingDepth how deep a value may nest
     * @param objectMembers how many members one object may carry
     * @param stringBytes how long one member name or string value may be
     */
    public record Bounds(long documentBytes, long nestingDepth, long objectMembers,
                         long stringBytes) {

        /**
         * The bounds the contract declares, which is where every one of them lives.
         *
         * @param contract the authenticated contract
         * @return the bounds
         */
        public static Bounds from(AgentContract contract) {
            return new Bounds(
                    contract.value(ContractLimit.MAXIMUM_AGENT_PROTOCOL_DOCUMENT_BYTES),
                    contract.value(ContractLimit.MAXIMUM_DOCUMENT_NESTING_DEPTH),
                    contract.value(ContractLimit.MAXIMUM_DOCUMENT_OBJECT_MEMBERS),
                    contract.value(ContractLimit.MAXIMUM_DOCUMENT_STRING_BYTES));
        }
    }

    /** The result of a read: the document, or the one reason there is none. */
    public sealed interface Outcome permits Read, Refused {
    }

    /**
     * A document that satisfied every bound and was complete.
     *
     * @param value the document's own value
     */
    public record Read(DocumentValue value) implements Outcome {
    }

    /**
     * A read that produced no document.
     *
     * @param refusal why there is none, and where reading stopped
     */
    public record Refused(DocumentRefusal refusal) implements Outcome {
    }

    /**
     * Reads a document out of bytes already in hand.
     *
     * @param document the bytes
     * @param bounds the bounds to read under
     * @return the document, or the one reason there is none
     */
    public static Outcome read(byte[] document, Bounds bounds) {
        try {
            return read(new ByteArrayInputStream(document), document.length, bounds);
        } catch (final IOException impossible) {
            // Nothing in memory fails part-way, and treating it as a malformed document would
            // report a defect here as a defect in whatever sent the bytes.
            throw new IllegalStateException("reading bytes already in hand failed", impossible);
        }
    }

    /**
     * Reads a document out of a stream, under the bounds it was given.
     *
     * @param stream the bytes as they arrive
     * @param declaredLength how many bytes the sender said there are
     * @param bounds the bounds to read under
     * @return the document, or the one reason there is none
     * @throws IOException if the stream itself fails, which is a different thing from a document
     *     that is not one and is reported separately for that reason
     */
    public static Outcome read(InputStream stream, long declaredLength, Bounds bounds)
            throws IOException {
        return new BoundedDocumentReader(stream, declaredLength, bounds).run();
    }

    private Outcome run() throws IOException {
        if (declaredLength > bounds.documentBytes()) {
            return refused(DocumentRefusal.Failure.DOCUMENT_BYTES, declaredLength
                    + " bytes were declared, past the bound of " + bounds.documentBytes());
        }
        try {
            // The top-level value sits inside nothing, so the bound counts the
            // containers a value is inside rather than the values themselves.
            final DocumentValue value = value(0);
            return complete(value);
        } catch (final Refusal refusal) {
            return new Refused(refusal.refusal());
        }
    }

    private Outcome complete(DocumentValue value) throws IOException, Refusal {
        final int trailing = readByte();
        if (trailing != END_OF_INPUT) {
            return refused(DocumentRefusal.Failure.TRAILING_BYTES,
                    "the document is complete and " + rendered(trailing) + " follows it");
        }
        if (consumed.get() != declaredLength) {
            return refused(DocumentRefusal.Failure.LENGTH_MISMATCH, declaredLength
                    + " bytes were declared and " + consumed.get() + " arrived");
        }
        return new Read(value);
    }

    private Outcome refused(DocumentRefusal.Failure failure, String detail) {
        return new Refused(new DocumentRefusal(failure, consumed.get(), detail));
    }

    private DocumentValue value(long depth) throws IOException, Refusal {
        if (depth > bounds.nestingDepth()) {
            throw refusal(DocumentRefusal.Failure.NESTING_DEPTH,
                    "a value nests " + depth + " deep, past the bound of " + bounds.nestingDepth());
        }
        final int start = nextSignificant();
        return switch (start) {
            case '{' -> mapping(depth);
            case '[' -> sequence(depth);
            case '"' -> new DocumentValue.Text(text());
            case 't' -> literal("rue", new DocumentValue.Flag(DocumentValue.Truth.TRUE));
            case 'f' -> literal("alse", new DocumentValue.Flag(DocumentValue.Truth.FALSE));
            case 'n' -> literal("ull", new DocumentValue.Nothing());
            case END_OF_INPUT -> throw unterminated("a value");
            default -> whole(start);
        };
    }

    private DocumentValue mapping(long depth) throws IOException, Refusal {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        int next = nextSignificant();
        while (next != '}') {
            member(members, next, depth);
            next = nextSignificant();
            if (next == ',') {
                next = nextSignificant();
                continue;
            }
            if (next != '}') {
                throw malformed(next, "a comma or the end of an object");
            }
        }
        return new DocumentValue.Mapping(members);
    }

    private void member(SequencedMap<String, DocumentValue> members, int start, long depth)
            throws IOException, Refusal {
        if (start != '"') {
            throw malformed(start, "a member name");
        }
        final String name = text();
        if (members.containsKey(name)) {
            throw refusal(DocumentRefusal.Failure.DUPLICATE_MEMBER,
                    name + " is named twice, and two readers would not agree which one won");
        }
        if (members.size() + 1 > bounds.objectMembers()) {
            throw refusal(DocumentRefusal.Failure.OBJECT_MEMBERS, "an object carries more than "
                    + bounds.objectMembers() + " members");
        }
        final int colon = nextSignificant();
        if (colon != ':') {
            throw malformed(colon, "a colon");
        }
        members.put(name, value(depth + 1));
    }

    private DocumentValue sequence(long depth) throws IOException, Refusal {
        final List<DocumentValue> items = new ArrayList<>();
        int next = nextSignificant();
        while (next != ']') {
            push(next);
            items.add(value(depth + 1));
            next = nextSignificant();
            if (next == ',') {
                next = nextSignificant();
                continue;
            }
            if (next != ']') {
                throw malformed(next, "a comma or the end of an array");
            }
        }
        return new DocumentValue.Sequence(items);
    }

    private String text() throws IOException, Refusal {
        final ByteArrayOutputStream held = new ByteArrayOutputStream();
        int next = readByte();
        while (next != '"') {
            if (next == END_OF_INPUT) {
                throw unterminated("a string");
            }
            if (next == '\\') {
                escaped(held);
            } else {
                held.write(next);
            }
            if (held.size() > bounds.stringBytes()) {
                throw refusal(DocumentRefusal.Failure.STRING_BYTES, "a name or string is longer"
                        + " than the bound of " + bounds.stringBytes() + " bytes");
            }
            next = readByte();
        }
        return held.toString(StandardCharsets.UTF_8);
    }

    private void escaped(ByteArrayOutputStream held) throws IOException, Refusal {
        final int marker = readByte();
        if (marker == 'u') {
            // A scalar is written back as the character it names rather than as the byte its low
            // half happens to be: the canonical form escapes only controls, and a reader that
            // truncated anything else would hand back a string nobody sent.
            held.writeBytes(character(scalar()));
            return;
        }
        held.write(switch (marker) {
            case '"', '\\', '/' -> marker;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case END_OF_INPUT -> throw unterminated("an escape");
            default -> throw malformed(marker, "an escape");
        });
    }

    private byte[] character(int scalar) throws Refusal {
        if (Character.isSurrogate((char) scalar)) {
            throw refusal(DocumentRefusal.Failure.MALFORMED,
                    "an escape names half of a character, which is not one");
        }
        return new String(Character.toChars(scalar)).getBytes(StandardCharsets.UTF_8);
    }

    private int scalar() throws IOException, Refusal {
        final byte[] digits = new byte[SCALAR_DIGITS];
        int index = 0;
        while (index < SCALAR_DIGITS) {
            final int digit = readByte();
            if (digit == END_OF_INPUT) {
                throw unterminated("an escape");
            }
            digits[index] = (byte) digit;
            index = index + 1;
        }
        return decoded(new String(digits, StandardCharsets.UTF_8));
    }

    /** How many digits the one escape form the canonical contract permits carries. */
    private static final int SCALAR_DIGITS = 4;

    /** What those digits are written in. */
    private static final int HEXADECIMAL = 16;

    private int decoded(String digits) throws Refusal {
        try {
            return Integer.parseInt(digits, HEXADECIMAL);
        } catch (final NumberFormatException notAScalar) {
            throw new Refusal(new DocumentRefusal(DocumentRefusal.Failure.MALFORMED, consumed.get(),
                    digits + " is not the four hexadecimal digits an escape carries"), notAScalar);
        }
    }

    private DocumentValue literal(String rest, DocumentValue value) throws IOException, Refusal {
        for (final char expected : rest.toCharArray()) {
            final int next = readByte();
            if (next != expected) {
                throw malformed(next, "the literal this began");
            }
        }
        return value;
    }

    private DocumentValue whole(int start) throws IOException, Refusal {
        final StringBuilder digits = new StringBuilder().append((char) start);
        int next = readByte();
        while (isNumeric(next)) {
            digits.append((char) next);
            next = readByte();
        }
        push(next);
        return new DocumentValue.Whole(parsed(digits.toString()));
    }

    private static boolean isNumeric(int next) {
        return next >= '0' && next <= '9' || next == '-' || next == '+' || next == '.'
                || next == 'e' || next == 'E';
    }

    private long parsed(String digits) throws Refusal {
        try {
            return Long.parseLong(digits);
        } catch (final NumberFormatException notWhole) {
            throw new Refusal(new DocumentRefusal(DocumentRefusal.Failure.NOT_WHOLE, consumed.get(),
                    digits + " is not a whole number the canonical form can carry"), notWhole);
        }
    }

    private int nextSignificant() throws IOException, Refusal {
        int next = readByte();
        while (next == ' ' || next == '\t' || next == '\n' || next == '\r') {
            next = readByte();
        }
        return next;
    }

    private void push(int byteRead) throws IOException {
        if (byteRead == END_OF_INPUT) {
            return;
        }
        stream.unread(byteRead);
        consumed.decrementAndGet();
    }

    private int readByte() throws IOException, Refusal {
        final int next = stream.read();
        if (next == END_OF_INPUT) {
            return END_OF_INPUT;
        }
        if (consumed.incrementAndGet() > bounds.documentBytes()) {
            throw refusal(DocumentRefusal.Failure.DOCUMENT_BYTES,
                    "the document is longer than the bound of " + bounds.documentBytes() + " bytes");
        }
        return next;
    }

    private Refusal unterminated(String what) {
        return refusal(DocumentRefusal.Failure.UNTERMINATED,
                "the input ends part-way through " + what);
    }

    private Refusal malformed(int found, String expected) {
        // An input that stopped where something had to be is unterminated rather than malformed:
        // nothing is wrong with what arrived, and what is missing is the rest of it.
        if (found == END_OF_INPUT) {
            return unterminated(expected);
        }
        return refusal(DocumentRefusal.Failure.MALFORMED,
                rendered(found) + " is where " + expected + " has to be");
    }

    private Refusal refusal(DocumentRefusal.Failure failure, String detail) {
        return new Refusal(new DocumentRefusal(failure, consumed.get(), detail));
    }

    private static String rendered(int found) {
        return found == END_OF_INPUT ? "the end of the input" : "'" + (char) found + "'";
    }

    /**
     * The one way a refusal travels out of the parse, caught where the outcome is built.
     *
     * <p>It is checked and private so that no refusal can leave this class as an exception: what a
     * caller sees is an outcome, and an outcome is either a document or a reason.</p>
     */
    private static final class Refusal extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient DocumentRefusal carried;

        private Refusal(DocumentRefusal carried) {
            this(carried, null);
        }

        private Refusal(DocumentRefusal carried, Throwable cause) {
            super(carried.rendered(), cause, false, false);
            this.carried = carried;
        }

        private DocumentRefusal refusal() {
            return carried;
        }
    }
}
