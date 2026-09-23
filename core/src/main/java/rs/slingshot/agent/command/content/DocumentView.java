// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

/**
 * One node in FileVault's document view: the {@code .content.xml} a package carries for it.
 *
 * <p>Written the way FileVault reads it back, because a package that does not install is not a
 * package. Every namespace a name or a value uses is declared; every value that is not a string
 * carries its type, so a date stays a date and a flag stays a flag; a multi-valued property is a
 * bracketed list; and a binary is not inlined at all but named as a file of its own beside the
 * document, which is the one place FileVault looks for it.</p>
 *
 * <p>Only the node's own properties are written. Its children are written as documents of their
 * own, each in its own directory, so what one document says is exactly one node.</p>
 */
final class DocumentView {

    /** The namespace every document's root element is in, whatever the node uses. */
    private static final String JCR_PREFIX = "jcr";

    /** The property that names a node's primary type. */
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    /** The property that names a node's mixins. */
    private static final String MIXIN_TYPES = "jcr:mixinTypes";

    /** What a binary property's attribute says, pointing at the file that holds it. */
    private static final String BINARY_MARKER = "{Binary}";

    /** The suffix FileVault reads a binary property's file by. */
    static final String BINARY_SUFFIX = ".binary";

    /** How many hexadecimal digits one escaped character of a name is written with. */
    private static final int HEXADECIMAL_DIGITS = 4;

    /** How long an escape's opening, {@code _x}, is. */
    private static final int ESCAPE_OPENING = 2;

    /** The radix an escape's digits are read in. */
    private static final int HEXADECIMAL = 16;

    /** Room for one document's opening and a handful of attributes before it has to grow. */
    private static final int DOCUMENT_CAPACITY = 512;

    private DocumentView() {
    }

    /**
     * One node's document and the binary properties it names as files of their own.
     *
     * @param document the {@code .content.xml} text
     * @param binaries each binary property's platform file name and its value, in name order
     */
    record Written(String document, SequencedMap<String, Value> binaries) {
    }

    /**
     * Writes one node.
     *
     * @param node the node
     * @return its document, and its binaries by file name
     * @throws RepositoryException if the repository fails
     */
    static Written of(Node node) throws RepositoryException {
        final Map<String, String> attributes = new TreeMap<>();
        final SequencedMap<String, Value> binaries = new java.util.LinkedHashMap<>();
        final SortedSet<String> prefixes = new TreeSet<>(List.of(JCR_PREFIX));
        final PropertyIterator properties = node.getProperties();
        final List<Property> ordered = new ArrayList<>();
        while (properties.hasNext()) {
            ordered.add(properties.nextProperty());
        }
        ordered.sort(java.util.Comparator.comparing(DocumentView::nameOf));
        for (final Property property : ordered) {
            read(property, attributes, binaries, prefixes);
        }
        return new Written(document(node, prefixes, attributes), binaries);
    }

    /**
     * Reads one property into the document's attributes, or its binaries where it is one.
     *
     * <p>A multi-valued binary is left out: FileVault holds one file per binary property, and a
     * list of them has no file it could be.</p>
     */
    private static void read(Property property, Map<String, String> attributes,
                             SequencedMap<String, Value> binaries, SortedSet<String> prefixes)
            throws RepositoryException {
        final String name = property.getName();
        prefixOf(name).ifPresent(prefixes::add);
        if (property.getType() == PropertyType.BINARY) {
            if (!property.isMultiple()) {
                attributes.put(name, BINARY_MARKER);
                binaries.put(PlatformNames.of(name) + BINARY_SUFFIX, property.getValue());
            }
            return;
        }
        final List<String> values = new ArrayList<>();
        for (final Value value : property.isMultiple() ? property.getValues()
                : new Value[] {property.getValue()}) {
            values.add(value.getString());
            if (property.getType() == PropertyType.NAME) {
                prefixOf(value.getString()).ifPresent(prefixes::add);
            }
        }
        // The two type properties are written bare, as FileVault writes them: a reader takes the
        // node's type from them before it reads a single typed value.
        final int type = PRIMARY_TYPE.equals(name) || MIXIN_TYPES.equals(name)
                ? PropertyType.STRING : property.getType();
        attributes.put(name, property.isMultiple() ? several(type, values)
                : single(type, values.getFirst()));
    }

    private static String nameOf(Property property) {
        try {
            return property.getName();
        } catch (final RepositoryException unnamed) {
            return "";
        }
    }

    private static String document(Node node, SortedSet<String> prefixes,
                                   Map<String, String> attributes) throws RepositoryException {
        final StringBuilder xml = new StringBuilder(DOCUMENT_CAPACITY)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<jcr:root");
        for (final String prefix : prefixes) {
            xml.append(" xmlns:").append(prefix).append("=\"")
                    .append(escaped(node.getSession().getNamespaceURI(prefix))).append('"');
        }
        // The two type properties first, as FileVault writes them, and the rest in name order.
        for (final String first : List.of(PRIMARY_TYPE, MIXIN_TYPES)) {
            if (attributes.containsKey(first)) {
                xml.append(' ').append(first).append("=\"").append(escaped(attributes.get(first)))
                        .append('"');
            }
        }
        attributes.forEach((name, value) -> {
            if (!PRIMARY_TYPE.equals(name) && !MIXIN_TYPES.equals(name)) {
                xml.append(' ').append(encodedName(name)).append("=\"").append(escaped(value))
                        .append('"');
            }
        });
        return xml.append("/>\n").toString();
    }

    /**
     * One single value as FileVault's document view spells it.
     *
     * <p>A string is written bare and every other type carries its name in braces, which is how
     * the reader knows a date is a date. A string that would otherwise read as a type or a list is
     * escaped where it begins.</p>
     *
     * @param type the property's type
     * @param value the value, as the repository spells it
     * @return the attribute value
     */
    static String single(int type, String value) {
        final String escapedValue = value.replace("\\", "\\\\");
        final boolean ambiguous = type == PropertyType.STRING
                && (escapedValue.startsWith("{") || escapedValue.startsWith("["));
        return typeOf(type) + (ambiguous ? "\\" : "") + escapedValue;
    }

    /**
     * Several values as FileVault's document view spells them: a bracketed list, each comma
     * inside a value escaped, after the type where it is not a string.
     *
     * @param type the property's type
     * @param values the values, as the repository spells each
     * @return the attribute value
     */
    static String several(int type, List<String> values) {
        return typeOf(type) + values.stream()
                .map(value -> value.replace("\\", "\\\\").replace(",", "\\,"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String typeOf(int type) {
        return type == PropertyType.STRING ? "" : "{" + PropertyType.nameFromValue(type) + "}";
    }

    /**
     * One name as an XML attribute name, escaping what XML does not allow as ISO 9075 does.
     *
     * @param name the repository name
     * @return the attribute name
     */
    static String encodedName(String name) {
        final int colon = name.indexOf(':');
        final String prefix = colon < 0 ? "" : name.substring(0, colon + 1);
        final String local = colon < 0 ? name : name.substring(colon + 1);
        return IntStream.range(0, local.length())
                .mapToObj(index -> encodedAt(local, index))
                .collect(java.util.stream.Collectors.joining("", prefix, ""));
    }

    private static String encodedAt(String local, int index) {
        final char character = local.charAt(index);
        final boolean allowed = Character.isLetter(character)
                || character == '_' && !escapesAt(local, index)
                || index > 0 && (Character.isDigit(character) || character == '-'
                || character == '.');
        return allowed ? String.valueOf(character) : "_x" + String.format(java.util.Locale.ROOT,
                "%0" + HEXADECIMAL_DIGITS + "X", (int) character) + "_";
    }

    /** Whether an underscore here would itself read as the start of an escape. */
    private static boolean escapesAt(String local, int index) {
        final int end = index + ESCAPE_OPENING + HEXADECIMAL_DIGITS;
        return end < local.length() && local.charAt(index + 1) == 'x'
                && local.charAt(end) == '_'
                && local.substring(index + ESCAPE_OPENING, end).chars().allMatch(character ->
                        Character.digit(character, HEXADECIMAL) >= 0);
    }

    private static java.util.Optional<String> prefixOf(String name) {
        final int colon = name.indexOf(':');
        return colon > 0 ? java.util.Optional.of(name.substring(0, colon))
                : java.util.Optional.empty();
    }

    /**
     * One value as an XML attribute's text.
     *
     * @param value the text
     * @return the escaped text
     */
    static String escaped(String value) {
        final StringBuilder escaped = new StringBuilder(value.length());
        for (final char character : value.toCharArray()) {
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\n' -> escaped.append("&#xA;");
                case '\r' -> escaped.append("&#xD;");
                case '\t' -> escaped.append("&#x9;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
