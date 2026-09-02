// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything one or more policy checks found, in one deterministic order.
 *
 * <p>A report is ordered by file, then line, then rule, then symbol, and rendering it twice over
 * the same tree produces identical bytes. That is not a nicety: a report whose order depends on a
 * directory walk is a report nobody can compare between two runs, and comparing two runs is the
 * only way to see what a change actually did to a repository's findings.</p>
 */
public final class PolicyReport {

    private static final String LINE_SEPARATOR = "\n";

    private final List<PolicyFinding> findings;

    private PolicyReport(List<PolicyFinding> findings) {
        this.findings = findings;
    }

    /**
     * A report holding nothing, which is what a check that refused nothing returns.
     *
     * @return the empty report
     */
    public static PolicyReport empty() {
        return new PolicyReport(List.of());
    }

    /**
     * Holds the given findings in the one deterministic order.
     *
     * @param findings the findings to report, in any order and with any repetition
     * @return the report, ordered and with repeated findings held once
     */
    public static PolicyReport of(Collection<PolicyFinding> findings) {
        return new PolicyReport(findings.stream().distinct().sorted().toList());
    }

    /**
     * Holds everything this report and another one found.
     *
     * @param other the other report
     * @return one report over both, in the same deterministic order
     */
    public PolicyReport and(PolicyReport other) {
        final List<PolicyFinding> both = new ArrayList<>(findings);
        both.addAll(other.findings);
        return of(both);
    }

    /**
     * The findings, in the order the report holds them.
     *
     * @return the ordered findings, which the caller cannot change
     */
    public List<PolicyFinding> findings() {
        return Collections.unmodifiableList(findings);
    }

    /**
     * Whether the checks that produced this report refused anything.
     *
     * @return {@code true} when nothing was found
     */
    public boolean isEmpty() {
        return findings.isEmpty();
    }

    /**
     * Renders every finding, one per line, in the report's own order.
     *
     * @return the rendered report, byte-identical across two runs over the same tree
     */
    public String render() {
        return findings.stream()
                .map(finding -> finding.render() + LINE_SEPARATOR)
                .collect(Collectors.joining());
    }

    @Override
    public String toString() {
        return render();
    }
}
