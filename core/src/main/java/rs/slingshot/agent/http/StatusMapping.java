// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import rs.slingshot.agent.wire.ErrorCode;

/**
 * What each refusal becomes on the wire, read from the committed mapping rather than decided here.
 *
 * <p>There is no default branch. A category with no row cannot be rendered at all, which is the
 * point: a default is a status somebody would have chosen if they had thought about it, and the
 * whole reason this is a file is that somebody did think about each one and wrote down why.</p>
 *
 * <p>Retryability travels with the status because they are one decision. A client reading a
 * service-unavailable status and being told the refusal is not retryable would have two answers to
 * one question, and the one it acts on would depend on which it read first.</p>
 */
public final class StatusMapping {

    /** Where the mapping is embedded in this bundle. */
    public static final String RESOURCE = "/rs/slingshot/agent/http/failure-status-mapping.toml";

    /** How a row's table is spelled in the document. */
    private static final String ROW_TABLE = "[row]";

    /** The key a table's own heading is kept under while a document is read. */
    private static final String TABLE = " table";

    private final SequencedMap<String, Row> rows;

    private StatusMapping(SequencedMap<String, Row> rows) {
        this.rows = rows;
    }

    /** Whether trying the same request again could ever produce a different answer. */
    public enum Retryable {
        /** It could: something on this side is expected to change. */
        WORTH_TRYING_AGAIN,
        /** It could not: the same request produces the same refusal for ever. */
        NEVER_WORTH_TRYING_AGAIN
    }

    /** Whether a refusal says how long to wait. */
    public enum Hint {
        /** It does. */
        CARRIES_A_HINT,
        /** It does not, because a hint on a refusal that cannot succeed wastes a request. */
        CARRIES_NONE
    }

    /**
     * One category's row.
     *
     * @param category the category, spelled as the protocol spells it
     * @param status what a caller is answered with
     * @param retryable whether trying again could ever produce a different answer
     * @param hint whether the refusal says how long to wait
     * @param reason why this row is what it is
     */
    public record Row(String category, int status, Retryable retryable, Hint hint, String reason) {

        /**
         * Whether trying again could ever help.
         *
         * @return whether it could
         */
        public boolean isWorthTryingAgain() {
            return retryable == Retryable.WORTH_TRYING_AGAIN;
        }

        /**
         * Whether this refusal says how long to wait.
         *
         * @return whether it does
         */
        public boolean carriesAhint() {
            return hint == Hint.CARRIES_A_HINT;
        }
    }

    /** Why a mapping was refused. */
    public enum Failure {
        /** The mapping is not embedded in this bundle at all. */
        UNREADABLE,
        /** The bytes are not a mapping document. */
        UNPARSABLE,
        /** A row is missing something every row states. */
        INCOMPLETE,
        /** A row carries a hint on a refusal that trying again cannot fix. */
        A_HINT_THAT_CANNOT_HELP
    }

    /** The result of reading the mapping. */
    public sealed interface Outcome permits Loaded, Refused {
    }

    /**
     * A mapping every row of which was read.
     *
     * @param mapping the mapping
     */
    public record Loaded(StatusMapping mapping) implements Outcome {
    }

    /**
     * One that was not.
     *
     * @param failure why not
     * @param detail what was observed
     */
    public record Refused(Failure failure, String detail) implements Outcome {
    }

    /**
     * Reads the mapping embedded in this bundle.
     *
     * @return the mapping, or the one reason there is none
     */
    public static Outcome load() {
        try (InputStream embedded = StatusMapping.class.getResourceAsStream(RESOURCE)) {
            if (embedded == null) {
                return new Refused(Failure.UNREADABLE,
                        "the failure mapping is not embedded in this bundle");
            }
            return read(new String(embedded.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("the embedded mapping could not be read", unreadable);
        }
    }

    /**
     * Reads a mapping out of its own bytes.
     *
     * @param document the mapping's text
     * @return the mapping, or the one reason it was refused
     */
    public static Outcome read(String document) {
        final List<SequencedMap<String, String>> tables = new ArrayList<>();
        SequencedMap<String, String> current = new LinkedHashMap<>();
        String heading = "";
        int number = 0;
        for (final String raw : document.lines().toList()) {
            number = number + 1;
            final String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '[') {
                if (line.charAt(line.length() - 1) != ']') {
                    return new Refused(Failure.UNPARSABLE, "line " + number + " is not a heading");
                }
                if (!current.isEmpty()) {
                    current.put(TABLE, heading);
                    tables.add(current);
                }
                current = new LinkedHashMap<>();
                heading = line.replace("[[", "[").replace("]]", "]").strip();
                continue;
            }
            final int assignment = line.indexOf('=');
            if (assignment < 1) {
                return new Refused(Failure.UNPARSABLE,
                        "line " + number + " is neither a heading nor an assignment");
            }
            current.put(line.substring(0, assignment).strip(),
                    unquote(line.substring(assignment + 1)));
        }
        if (!current.isEmpty()) {
            current.put(TABLE, heading);
            tables.add(current);
        }
        return bind(tables);
    }

    private static Outcome bind(List<SequencedMap<String, String>> tables) {
        final SequencedMap<String, Row> rows = new LinkedHashMap<>();
        for (final SequencedMap<String, String> table : tables) {
            if (!ROW_TABLE.equals(table.get(TABLE))) {
                continue;
            }
            final Optional<Refused> refusal = add(rows, table);
            if (refusal.isPresent()) {
                return refusal.get();
            }
        }
        return rows.isEmpty()
                ? new Refused(Failure.UNPARSABLE, "the mapping declares no row at all")
                : new Loaded(new StatusMapping(rows));
    }

    private static Optional<Refused> add(SequencedMap<String, Row> rows,
                                         SequencedMap<String, String> table) {
        final String category = table.getOrDefault("category", "");
        final String status = table.getOrDefault("status", "");
        final String reason = table.getOrDefault("reason", "");
        if (category.isBlank() || status.isBlank() || reason.isBlank()) {
            return Optional.of(new Refused(Failure.INCOMPLETE,
                    "a row states no category, status, or reason: " + table));
        }
        final Retryable retryable = "true".equals(table.getOrDefault("retryable", ""))
                ? Retryable.WORTH_TRYING_AGAIN
                : Retryable.NEVER_WORTH_TRYING_AGAIN;
        final Hint hint = "true".equals(table.getOrDefault("hint", ""))
                ? Hint.CARRIES_A_HINT
                : Hint.CARRIES_NONE;
        if (hint == Hint.CARRIES_A_HINT && retryable == Retryable.NEVER_WORTH_TRYING_AGAIN) {
            return Optional.of(new Refused(Failure.A_HINT_THAT_CANNOT_HELP, category
                    + " is not retryable and carries a hint, which is an instruction to waste a"
                    + " request"));
        }
        rows.put(category, new Row(category, Integer.parseInt(status), retryable, hint, reason));
        return Optional.empty();
    }

    /**
     * The row one category falls under.
     *
     * @param category the category, spelled as the protocol spells it
     * @return the row, or nothing where the mapping declares none, which is a build failure rather
     *     than a default
     */
    public Optional<Row> forCategory(String category) {
        return Optional.ofNullable(rows.get(category));
    }

    /**
     * The row one code falls under.
     *
     * @param code the code
     * @return the row, or nothing where the mapping declares none
     */
    public Optional<Row> forCode(ErrorCode code) {
        return forCategory(code.spelling());
    }

    /**
     * Every row this mapping declares, in the order the document declares them.
     *
     * @return the rows
     */
    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    /**
     * Every category this mapping declares.
     *
     * @return the categories, in the order the document declares them
     */
    public List<String> categories() {
        return List.copyOf(rows.keySet());
    }

    private static String stripComment(String line) {
        final int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static String unquote(String value) {
        final String stripped = value.strip();
        return stripped.length() > 1 && stripped.charAt(0) == '"'
                && stripped.charAt(stripped.length() - 1) == '"'
                ? stripped.substring(1, stripped.length() - 1)
                : stripped;
    }
}
