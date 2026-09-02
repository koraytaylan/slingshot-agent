// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The one reader every policy and support document in this repository is loaded through.
 *
 * <p>A document is read against a {@link Shape} that closes its key set: a key the shape does not
 * declare is a failure rather than a value nobody reads, and a declared key that is absent or
 * carries the wrong type is a failure too. The four ways a load can fail are distinct, and none of
 * them leaves a partly-populated document reachable — a check that ran against half a policy is
 * worse than a check that did not run.</p>
 *
 * <p>Loading answers a closed {@link Outcome} rather than throwing, because a refusal is a result
 * this repository's callers are expected to handle and report, not an accident.</p>
 */
public final class PolicyDocument {

    private final String kind;
    private final TomlTable table;
    private final SequencedSet<String> keys;

    private PolicyDocument(String kind, TomlTable table, SequencedSet<String> keys) {
        this.kind = kind;
        this.table = table;
        this.keys = keys;
    }

    /** Why a document was refused. Each cause is distinct because each has a different fix. */
    public enum Failure {
        /** The bytes are not a well-formed document at all. */
        UNPARSABLE,
        /** A key or a table is defined twice, so two values disagree quietly. */
        DUPLICATE_KEY,
        /** A key is present that the document's shape does not declare. */
        UNKNOWN_KEY,
        /** A declared key is absent, and this shape infers nothing. */
        MISSING_KEY,
        /** A declared key carries a value outside the type its shape declares. */
        WRONG_TYPE,
        /** The document is not there to read. */
        UNREADABLE
    }

    /** The result of a load: either a whole document or a refusal naming what was wrong. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A document that satisfied its shape completely.
     *
     * @param document the loaded document
     */
    public record Loaded(PolicyDocument document) implements Outcome {
    }

    /**
     * A load that produced no document.
     *
     * @param failure why the document was refused
     * @param detail what was refused, named so that somebody can fix it
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Reads a document and holds it to a shape.
     *
     * @param file the document to read
     * @param shape the closed key set the document is held to
     * @return the loaded document, or the one reason it was refused
     */
    public static Outcome load(Path file, Shape shape) {
        final String text;
        try {
            text = Files.readString(file);
        } catch (final IOException failure) {
            return new Refused(Failure.UNREADABLE, file + ": " + failure.getMessage());
        }
        return parse(text, shape, file.toString());
    }

    /**
     * Holds already-read bytes to a shape, for the fixtures that arrive as text rather than as a
     * file.
     *
     * @param text the document's text
     * @param shape the closed key set the document is held to
     * @param origin what to name in a refusal, usually a path
     * @return the loaded document, or the one reason it was refused
     */
    public static Outcome parse(String text, Shape shape, String origin) {
        final TomlParseResult parsed = Toml.parse(text);
        if (!parsed.errors().isEmpty()) {
            final String first = parsed.errors().getFirst().getMessage();
            final Failure failure = first.contains("previously defined")
                    ? Failure.DUPLICATE_KEY
                    : Failure.UNPARSABLE;
            return new Refused(failure, origin + ": " + first);
        }
        return shape.hold(parsed, origin);
    }

    /**
     * The kind of document this is, as its shape names it.
     *
     * @return the document kind
     */
    public String kind() {
        return kind;
    }

    /**
     * Every key this document carries, in the order its shape declares them.
     *
     * @return the document's key set
     */
    public SequencedSet<String> keys() {
        return keys;
    }

    /**
     * The text a declared key carries.
     *
     * @param key the dotted key
     * @return the value, which the shape has already proved is present and is text
     * @throws IllegalStateException if the key is not one this document's shape declares as text
     */
    public String text(String key) {
        return Optional.ofNullable(table.getString(key))
                .orElseThrow(() -> new IllegalStateException(kind + " declares no text at " + key));
    }

    /**
     * The whole number a declared key carries.
     *
     * @param key the dotted key
     * @return the value, which the shape has already proved is present and is a whole number
     * @throws IllegalStateException if the key is not one this document's shape declares as a
     *     whole number
     */
    public long number(String key) {
        return Optional.ofNullable(table.getLong(key))
                .orElseThrow(() -> new IllegalStateException(kind + " declares no number at " + key));
    }

    /**
     * The two-valued answer a declared key carries.
     *
     * @param key the dotted key
     * @return the value, which the shape has already proved is present and is two-valued
     * @throws IllegalStateException if the key is not one this document's shape declares as
     *     two-valued
     */
    public boolean answer(String key) {
        return Optional.ofNullable(table.getBoolean(key))
                .orElseThrow(() -> new IllegalStateException(kind + " declares no answer at " + key));
    }

    /**
     * The text a declared optional key carries, where it carries one.
     *
     * @param key the dotted key
     * @return the value, or nothing where the document omits the key
     */
    public Optional<String> optionalText(String key) {
        return Optional.ofNullable(table.getString(key));
    }

    /**
     * The text values a declared list carries.
     *
     * @param key the dotted key
     * @return the values, in the order the document declares them
     */
    public List<String> textList(String key) {
        final TomlArray array = table.getArray(key);
        if (array == null) {
            return List.of();
        }
        return IntStream.range(0, array.size())
                .mapToObj(array::getString)
                .toList();
    }

    /**
     * The rows of a declared repeated table, each held to the row shape its declaration gave.
     *
     * @param key the dotted key the repeated table sits at
     * @return the rows, in the order the document declares them
     */
    public List<PolicyDocument> rows(String key) {
        final TomlArray array = table.getArray(key);
        if (array == null) {
            return List.of();
        }
        return IntStream.range(0, array.size())
                .mapToObj(array::getTable)
                .map(row -> new PolicyDocument(kind + "." + key, row, orderedKeys(row)))
                .toList();
    }

    /**
     * The keys and text values of a declared free-form table, whose keys the shape does not close.
     *
     * @param key the dotted key the table sits at
     * @return the table's keys and their text values, in the document's own order
     */
    public SequencedMap<String, String> freeTable(String key) {
        final TomlTable free = table.getTable(key);
        final SequencedMap<String, String> values = new LinkedHashMap<>();
        if (free == null) {
            return values;
        }
        free.dottedKeySet().stream()
                .sorted()
                .forEach(name -> values.put(name, String.valueOf(free.get(name))));
        return values;
    }

    private static SequencedSet<String> orderedKeys(TomlTable table) {
        return new LinkedHashSet<>(table.dottedKeySet().stream().sorted().toList());
    }

    /**
     * The closed key set one kind of document is held to.
     *
     * <p>A shape is built once, beside the check that reads the document, so that the set of keys a
     * document may carry and the code that reads them cannot drift apart.</p>
     */
    public static final class Shape {

        private final String kind;
        private final List<Key> declared;

        private Shape(String kind, List<Key> declared) {
            this.kind = kind;
            this.declared = declared;
        }

        /**
         * Starts declaring the shape of one kind of document.
         *
         * @param kind the document kind, named as the file names it
         * @return a builder for that kind
         */
        public static Builder named(String kind) {
            return new Builder(kind);
        }

        private Outcome hold(TomlTable parsed, String origin) {
            final SequencedSet<String> covered = new LinkedHashSet<>();
            for (final Key key : declared) {
                final Optional<Refused> refusal = key.check(parsed, origin);
                if (refusal.isPresent()) {
                    return refusal.get();
                }
                covered.add(key.name());
            }
            final Optional<String> unknown = unknownKey(parsed);
            if (unknown.isPresent()) {
                return new Refused(Failure.UNKNOWN_KEY, origin + ": " + unknown.get());
            }
            return new Loaded(new PolicyDocument(kind, parsed, covered));
        }

        private Optional<String> unknownKey(TomlTable parsed) {
            return parsed.dottedKeySet().stream()
                    .sorted()
                    .filter(present -> declared.stream().noneMatch(key -> key.covers(present)))
                    .findFirst();
        }

        /** Builds one document shape. */
        public static final class Builder {

            private final String kind;
            private final List<Key> declared = new ArrayList<>();

            private Builder(String kind) {
                this.kind = kind;
            }

            /**
             * Declares a required key carrying text.
             *
             * @param key the dotted key
             * @return this builder
             */
            public Builder text(String key) {
                declared.add(new Key(key, Kind.TEXT, Presence.REQUIRED));
                return this;
            }

            /**
             * Declares a key carrying text that a document may omit.
             *
             * @param key the dotted key
             * @return this builder
             */
            public Builder optionalText(String key) {
                declared.add(new Key(key, Kind.TEXT, Presence.OPTIONAL));
                return this;
            }

            /**
             * Declares a required key carrying a list of text values.
             *
             * @param key the dotted key
             * @return this builder
             */
            public Builder textList(String key) {
                declared.add(new Key(key, Kind.TEXT_LIST, Presence.REQUIRED));
                return this;
            }

            /**
             * Declares a key carrying a list of text values that a document may omit.
             *
             * <p>An omitted list is no values rather than a missing key, so a document with
             * nothing to say under one heading says nothing rather than failing to load.</p>
             *
             * @param key the dotted key
             * @return this builder
             */
            public Builder optionalTextList(String key) {
                declared.add(new Key(key, Kind.TEXT_LIST, Presence.OPTIONAL));
                return this;
            }

            /**
             * Declares a required key carrying a whole number.
             *
             * @param key the dotted key
             * @return this builder
             */
            public Builder number(String key) {
                declared.add(new Key(key, Kind.NUMBER, Presence.REQUIRED));
                return this;
            }

            /**
             * Declares a required key carrying a two-valued answer.
             *
             * @param key the dotted key
             * @return this builder
             */
            public Builder answer(String key) {
                declared.add(new Key(key, Kind.ANSWER, Presence.REQUIRED));
                return this;
            }

            /**
             * Declares a required table whose own keys this shape does not close, for the places a
             * document records values it was given rather than values it chose.
             *
             * @param key the dotted key the table sits at
             * @return this builder
             */
            public Builder freeTable(String key) {
                declared.add(new Key(key, Kind.FREE_TABLE, Presence.REQUIRED));
                return this;
            }

            /**
             * Declares a repeated table and the closed key set each of its rows is held to.
             *
             * <p>A repeated table the document omits is zero rows rather than a missing key, so a
             * policy with nothing to say under one heading says nothing rather than failing to
             * load. Every row the document does declare is held to the row shape completely.</p>
             *
             * @param key the key the repeated table sits at
             * @param row the row's own shape, declared the same way
             * @return this builder
             */
            public Builder rows(String key, UnaryOperator<Builder> row) {
                final Builder rowBuilder = row.apply(new Builder(kind + "." + key));
                declared.add(new Key(key, Kind.ROWS, Presence.OPTIONAL,
                        List.copyOf(rowBuilder.declared)));
                return this;
            }

            /**
             * Closes the shape.
             *
             * @return the shape, which nothing can change afterwards
             */
            public Shape build() {
                return new Shape(kind, List.copyOf(declared));
            }
        }
    }

    private enum Kind { TEXT, TEXT_LIST, NUMBER, ANSWER, FREE_TABLE, ROWS }

    private enum Presence { REQUIRED, OPTIONAL }

    private record Key(String name, Kind kind, Presence presence, List<Key> rowShape) {

        Key(String name, Kind kind, Presence presence) {
            this(name, kind, presence, List.of());
        }

        boolean covers(String present) {
            return present.equals(name)
                    || (kind == Kind.FREE_TABLE || kind == Kind.ROWS)
                            && present.startsWith(name + ".");
        }

        Optional<Refused> check(TomlTable parsed, String origin) {
            final Object value = parsed.get(name);
            if (value == null) {
                return presence == Presence.OPTIONAL
                        ? Optional.empty()
                        : Optional.of(new Refused(Failure.MISSING_KEY, origin + ": " + name));
            }
            return switch (kind) {
                case TEXT -> typed(value, String.class, origin, "text");
                case TEXT_LIST -> checkTextList(value, origin);
                case NUMBER -> typed(value, Long.class, origin, "a whole number");
                case ANSWER -> typed(value, Boolean.class, origin, "a two-valued answer");
                case FREE_TABLE -> typed(value, TomlTable.class, origin, "a table");
                case ROWS -> checkRows(value, origin);
            };
        }

        private Optional<Refused> typed(Object value, Class<?> expected, String origin,
                                        String described) {
            return expected.isInstance(value)
                    ? Optional.empty()
                    : Optional.of(new Refused(Failure.WRONG_TYPE,
                            origin + ": " + name + " is not " + described));
        }

        private Optional<Refused> checkTextList(Object value, String origin) {
            if (!(value instanceof final TomlArray array)) {
                return Optional.of(new Refused(Failure.WRONG_TYPE,
                        origin + ": " + name + " is not a list"));
            }
            final boolean everyEntryIsText = IntStream.range(0, array.size())
                    .mapToObj(array::get)
                    .allMatch(String.class::isInstance);
            return everyEntryIsText
                    ? Optional.empty()
                    : Optional.of(new Refused(Failure.WRONG_TYPE,
                            origin + ": " + name + " holds something that is not text"));
        }

        private Optional<Refused> checkRows(Object value, String origin) {
            if (!(value instanceof final TomlArray array)) {
                return Optional.of(new Refused(Failure.WRONG_TYPE,
                        origin + ": " + name + " is not a repeated table"));
            }
            return IntStream.range(0, array.size())
                    .mapToObj(index -> array.get(index) instanceof final TomlTable row
                            ? checkRow(row, origin, index)
                            : Optional.of(new Refused(Failure.WRONG_TYPE,
                                    origin + ": " + name + " holds something that is not a table")))
                    .flatMap(Optional::stream)
                    .findFirst();
        }

        private Optional<Refused> checkRow(TomlTable row, String origin, int index) {
            final String place = origin + ": " + name + "[" + index + "]";
            for (final Key declared : rowShape) {
                final Optional<Refused> refusal = declared.check(row, place);
                if (refusal.isPresent()) {
                    return refusal;
                }
            }
            return row.dottedKeySet().stream()
                    .sorted()
                    .filter(present -> rowShape.stream()
                            .noneMatch(declared -> declared.covers(present)))
                    .findFirst()
                    .map(unknown -> new Refused(Failure.UNKNOWN_KEY, place + "." + unknown));
        }
    }
}
