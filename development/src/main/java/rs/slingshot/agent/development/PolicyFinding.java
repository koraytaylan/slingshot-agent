// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.util.Comparator;

/**
 * One thing a policy check found, at the place it found it.
 *
 * <p>A finding is the only shape a check reports in. Holding the file, the line, the rule, and the
 * symbol separately rather than as one rendered sentence is what lets a report be ordered
 * deterministically, and what stops two checks disagreeing about what a finding is.</p>
 *
 * @param file the repository-relative path of the file the finding is in
 * @param line the one-based line the finding sits on, or {@link #NO_LINE} where the finding is
 *     about the file as a whole rather than about a place in it
 * @param rule the identifier of the rule that refused, spelled the same way everywhere it appears
 * @param symbol the declared name, key, package, or coordinate the rule refused
 */
public record PolicyFinding(String file, int line, String rule, String symbol)
        implements Comparable<PolicyFinding> {

    /** The line of a finding about a file as a whole rather than about a place inside it. */
    public static final int NO_LINE = 0;

    private static final Comparator<PolicyFinding> ORDER =
            Comparator.comparing(PolicyFinding::file)
                    .thenComparingInt(PolicyFinding::line)
                    .thenComparing(PolicyFinding::rule)
                    .thenComparing(PolicyFinding::symbol);

    /**
     * Holds a finding whose every part is stated.
     *
     * @throws IllegalArgumentException if any part is blank or the line is negative, because a
     *     finding nobody can locate is a finding nobody can act on
     */
    public PolicyFinding {
        requireStated(file, "file");
        requireStated(rule, "rule");
        requireStated(symbol, "symbol");
        if (line < NO_LINE) {
            throw new IllegalArgumentException("a finding cannot sit on line " + line);
        }
    }

    /**
     * Holds a finding about a file as a whole.
     *
     * @param file the repository-relative path of the file the finding is in
     * @param rule the identifier of the rule that refused
     * @param symbol the declared name, key, package, or coordinate the rule refused
     * @return the finding, on {@link #NO_LINE}
     */
    public static PolicyFinding inFile(String file, String rule, String symbol) {
        return new PolicyFinding(file, NO_LINE, rule, symbol);
    }

    /**
     * Renders the finding as one line, in the one form every check reports in.
     *
     * @return the rendered finding, which is stable across runs and across machines
     */
    public String render() {
        final String place = line == NO_LINE ? file : file + ":" + line;
        return place + "\t" + rule + "\t" + symbol;
    }

    @Override
    public int compareTo(PolicyFinding other) {
        return ORDER.compare(this, other);
    }

    private static void requireStated(String value, String part) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("a finding states no " + part);
        }
    }
}
