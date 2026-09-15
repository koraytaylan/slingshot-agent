// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import rs.slingshot.agent.json.DocumentValue;

/**
 * How one repository value becomes a document value, with nothing mapped by a default branch.
 *
 * <p>Every type the repository can hold is named here and turned into something a caller can read
 * back. A type this build does not understand is refused by name rather than rendered as its string
 * form, because a value nobody can round-trip is worse than a value nobody received: a caller who
 * receives it cannot tell a genuine string from a coerced binary, and a caller who writes it back
 * writes something else.</p>
 *
 * <p>There is no default branch. The switch is over the repository's own closed set of type codes,
 * so a type added to that set stops the build here rather than arriving at a caller wearing
 * whichever representation the fallback happened to use.</p>
 */
public enum RepositoryValueKind {
    /** Text, which is carried as text. */
    STRING(PropertyType.STRING, "string"),
    /** A binary, carried as metadata alone. */
    BINARY(PropertyType.BINARY, "binary"),
    /** A whole number, which is carried as text. */
    LONG(PropertyType.LONG, "long"),
    /** A truth, which is carried as a truth rather than as the word for one. */
    BOOLEAN(PropertyType.BOOLEAN, "boolean"),
    /** A decimal, carried as text so no precision is lost on the way through. */
    DECIMAL(PropertyType.DECIMAL, "decimal"),
    /** A floating-point number, carried as text for the same reason. */
    DOUBLE(PropertyType.DOUBLE, "double"),
    /** An instant, carried in the repository's own written form. */
    DATE(PropertyType.DATE, "date"),
    /** A name, which is a repository name rather than arbitrary text. */
    NAME(PropertyType.NAME, "name"),
    /** A path, which is a repository path rather than arbitrary text. */
    PATH(PropertyType.PATH, "path"),
    /** A reference to another node, carried as the identifier it names. */
    REFERENCE(PropertyType.REFERENCE, "reference"),
    /** A reference that does not hold its target, carried the same way. */
    WEAKREFERENCE(PropertyType.WEAKREFERENCE, "weak_reference"),
    /** A uniform resource identifier. */
    URI(PropertyType.URI, "uri");

    private final int code;
    private final String spelling;

    RepositoryValueKind(int code, String spelling) {
        this.code = code;
        this.spelling = spelling;
    }

    /**
     * The repository's own code for this type.
     *
     * @return the code
     */
    public int code() {
        return code;
    }

    /**
     * How this type is spelled in a rendered document.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * Which supported type one repository code is, where it is one this build understands.
     *
     * @param code the repository's own type code
     * @return the type, or nothing where this build does not represent it faithfully
     */
    public static Optional<RepositoryValueKind> of(int code) {
        return java.util.stream.Stream.of(values())
                .filter(supported -> supported.code() == code)
                .findFirst();
    }

    /**
     * What this build calls a type it will not render.
     *
     * <p>Named rather than numbered, because the caller reading the refusal is looking at their own
     * content and needs to know what is in it.</p>
     *
     * @param code the repository's own type code
     * @return the repository's own name for it, or the code where even that is unknown
     */
    public static String unsupportedName(int code) {
        try {
            return PropertyType.nameFromValue(code);
        } catch (final IllegalArgumentException unknown) {
            return "type-" + code;
        }
    }

    /**
     * The document value one supported type and its repository value become.
     *
     * <p>Every type but a truth is carried as text, in the one spelling the client's own reader
     * accepts for it. That is not a formatting choice: the reader validates each spelling against
     * the type it was told to expect, so a value written another way is not a value it can read.
     * A long must be its minimal decimal spelling, because the client compares digits rather than
     * parsing a number that could have been rounded; a double must be the sixteen lowercase
     * hexadecimal digits of its binary64 bits, because a decimal rendering could not carry a
     * non-finite value and could not round-trip; and a binary carries its length and never its
     * bytes, because a length is what the client's own reader asks for and reading a stream to
     * answer a question about its size would read content the caller did not ask to see.</p>
     *
     * @param kind the type
     * @param value the repository value
     * @param length the length of the value, which a binary carries instead of its bytes
     * @return the document value
     * @throws RepositoryException if the repository fails
     */
    public static DocumentValue documentValueOf(RepositoryValueKind kind, Value value, long length)
            throws RepositoryException {
        try {
            return switch (kind) {
                case LONG -> new DocumentValue.Text(minimalDecimalOf(value.getLong()));
                case BOOLEAN -> new DocumentValue.Flag(value.getBoolean()
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE);
                case DOUBLE -> new DocumentValue.Text(binary64BitsOf(value.getDouble()));
                case BINARY -> {
                    final SequencedMap<String, DocumentValue> metadata = new LinkedHashMap<>();
                    metadata.put(BYTE_LENGTH, new DocumentValue.Text(String.valueOf(length)));
                    yield new DocumentValue.Mapping(metadata);
                }
                case STRING, DECIMAL, DATE, NAME, PATH, REFERENCE, WEAKREFERENCE, URI ->
                        new DocumentValue.Text(value.getString());
            };
        } catch (final javax.jcr.ValueFormatException failure) {
            throw new RepositoryException(failure);
        }
    }

    /** The member a binary value's length is carried in. */
    public static final String BYTE_LENGTH = "byte_length";

    /** How many hexadecimal digits one binary64 value is written with. */
    private static final int BINARY64_DIGITS = 16;

    /**
     * One whole number in the minimal decimal spelling the client's reader compares.
     *
     * @param whole the number
     * @return its minimal decimal spelling
     */
    private static String minimalDecimalOf(long whole) {
        return Long.toString(whole);
    }

    /**
     * One floating-point number as the bits it is, which is the only spelling that survives.
     *
     * <p>Sixteen lowercase hexadecimal digits of the binary64 value, which is what the client's
     * reader requires and what no decimal rendering could promise: a non-finite value has no
     * decimal spelling at all, and a decimal one would be read back as a different bit pattern.</p>
     *
     * @param value the floating-point number
     * @return its binary64 bits, in lowercase hexadecimal
     */
    private static String binary64BitsOf(double value) {
        return String.format("%0" + BINARY64_DIGITS + "x", Double.doubleToRawLongBits(value));
    }
}
