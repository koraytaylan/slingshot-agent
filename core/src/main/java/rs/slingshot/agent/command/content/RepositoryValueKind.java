// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Optional;
import javax.jcr.PropertyType;
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
    /** A whole number, which is carried as a whole number. */
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
     * The document value one supported type and its written form become.
     *
     * <p>A whole number and a truth are carried as themselves; everything else is carried as text,
     * in the repository's own written form, because text is the only representation this protocol
     * has that survives a round trip without a lossy conversion in the middle. What each one is
     * travels beside it, so a caller writing a value back knows what to write.</p>
     *
     * @param kind the type
     * @param written the value as the repository writes it
     * @return the document value
     */
    public static DocumentValue documentValueOf(RepositoryValueKind kind, String written) {
        return switch (kind) {
            case LONG -> wholeOr(written);
            case BOOLEAN -> new DocumentValue.Flag(Boolean.parseBoolean(written)
                    ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE);
            case STRING, DECIMAL, DOUBLE, DATE, NAME, PATH, REFERENCE, WEAKREFERENCE, URI ->
                    new DocumentValue.Text(written);
        };
    }

    private static DocumentValue wholeOr(String written) {
        try {
            return new DocumentValue.Whole(Long.parseLong(written));
        } catch (final NumberFormatException notWhole) {
            // A repository that answered a long property with something that is not one is a
            // repository disagreeing with itself. Carrying the text is the honest answer: it says
            // what was actually there rather than a number nobody stored.
            return new DocumentValue.Text(written);
        }
    }
}
