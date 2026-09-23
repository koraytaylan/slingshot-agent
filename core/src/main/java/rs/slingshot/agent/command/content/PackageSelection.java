// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * One package selection expression: the client's own language, read the client's own way.
 *
 * <p>An expression is a sequence of tokens, each of which is a literal path segment, {@code *}
 * for exactly one segment, or {@code (.*)} for zero or more. Nothing else is special: a literal
 * segment holding a dot, a plus, or a bracket is compared exactly, because a caller writing a path
 * should not have to know which characters some engine would have read as syntax. An expression
 * matches a whole path, never part of one, which is what lets the client promise a caller what a
 * filter selects before anything is built.</p>
 *
 * <p>Matching fills a table of {@code (tokens + 1) x (segments + 1)} cells once each, with no
 * backtracking and no engine, so the cost of one expression against one path is known before it
 * is paid.</p>
 */
final class PackageSelection {

    /** What separates two tokens, and what every expression begins with. */
    static final char SEPARATOR = '/';

    /** The token matching exactly one segment. */
    static final String ONE_SEGMENT = "*";

    /** The token matching zero or more segments. */
    static final String ANY_SEGMENTS = "(.*)";

    /** What one token is. */
    private enum Kind {
        /** One segment, spelled exactly. */
        LITERAL,
        /** Exactly one segment, whatever it is. */
        ONE,
        /** Zero or more segments. */
        ANY
    }

    /**
     * One token of an expression.
     *
     * @param kind what it matches
     * @param literal the segment it names, which is empty unless it is a literal
     */
    private record Token(Kind kind, String literal) {
    }

    private final List<Token> tokens;

    private PackageSelection(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    /**
     * The expression one spelling is, where it is one.
     *
     * @param spelling what the caller wrote
     * @return the expression, or nothing where the spelling is not one this language has
     */
    static Optional<PackageSelection> of(String spelling) {
        if (spelling.isEmpty() || spelling.charAt(0) != SEPARATOR) {
            return Optional.empty();
        }
        final String body = spelling.substring(1);
        if (body.isEmpty()) {
            return Optional.of(new PackageSelection(List.of()));
        }
        final List<String> spellings = Arrays.asList(body.split(String.valueOf(SEPARATOR), -1));
        if (spellings.stream().anyMatch(String::isEmpty)) {
            return Optional.empty();
        }
        final List<Token> read = spellings.stream().map(PackageSelection::tokenOf).toList();
        return read.stream().anyMatch(token -> token.kind() == Kind.LITERAL
                && token.literal().indexOf('*') >= 0)
                ? Optional.empty()
                : Optional.of(new PackageSelection(read));
    }

    private static Token tokenOf(String spelling) {
        if (ONE_SEGMENT.equals(spelling)) {
            return new Token(Kind.ONE, "");
        }
        if (ANY_SEGMENTS.equals(spelling)) {
            return new Token(Kind.ANY, "");
        }
        return new Token(Kind.LITERAL, spelling);
    }

    /**
     * Whether this expression matches one whole path.
     *
     * @param path an absolute repository path
     * @return whether it does
     */
    boolean matches(String path) {
        final List<String> segments = segmentsOf(path);
        final boolean[][] reachable = new boolean[tokens.size() + 1][segments.size() + 1];
        reachable[tokens.size()][segments.size()] = true;
        // Filled from the last token and the last segment backwards, so every cell a cell reads
        // is already filled when it is read.
        IntStream.iterate(tokens.size() - 1, token -> token >= 0, token -> token - 1)
                .forEach(token -> IntStream.iterate(segments.size(), segment -> segment >= 0,
                                segment -> segment - 1)
                        .forEach(segment -> reachable[token][segment] =
                                reached(reachable, token, segments, segment)));
        return reachable[0][0];
    }

    /**
     * Whether this expression matches one path or any of its ancestors.
     *
     * <p>Which is what makes a matched path an anchor: the anchor and everything beneath it are
     * what the expression selects or removes.</p>
     *
     * @param path an absolute repository path
     * @return whether it does
     */
    boolean anchors(String path) {
        final List<String> segments = segmentsOf(path);
        return IntStream.rangeClosed(0, segments.size())
                .anyMatch(length -> matches(SEPARATOR + String.join(String.valueOf(SEPARATOR),
                        segments.subList(0, length))));
    }

    private boolean reached(boolean[][] reachable, int token, List<String> segments,
                            int segment) {
        final boolean more = segment < segments.size();
        final Token held = tokens.get(token);
        return switch (held.kind()) {
            case ANY -> reachable[token + 1][segment] || more && reachable[token][segment + 1];
            case ONE -> more && reachable[token + 1][segment + 1];
            case LITERAL -> more && segments.get(segment).equals(held.literal())
                    && reachable[token + 1][segment + 1];
        };
    }

    private static List<String> segmentsOf(String path) {
        final String body = path.startsWith(String.valueOf(SEPARATOR)) ? path.substring(1) : path;
        return body.isEmpty() ? List.of()
                : Arrays.asList(body.split(String.valueOf(SEPARATOR), -1));
    }
}
