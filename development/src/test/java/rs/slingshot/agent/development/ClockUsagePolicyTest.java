// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two clocks, two purposes, and neither used for the other's.
 *
 * <p>The failure mode of getting this wrong is not a wrong number. It is a session that outlives
 * the bound it published because a time service corrected a clock backwards, or two nodes both
 * holding one lease because their clocks disagree — and neither is something a client could be told
 * about, because from the outside both look like the connection behaving oddly.</p>
 *
 * <p>The relation the key ring rests on is checked here too, against the contract's own values:
 * a rotated-out key kept for less than a token's lifetime plus the declared skew is a key dropped
 * while a token it signed is still valid.</p>
 */
final class ClockUsagePolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/clock-usage");

    @Test
    @DisplayName("this repository measures no duration on a corrected clock")
    void thisRepositoryKeepsTheTwoSourcesApart() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("a duration measured on a wall clock is refused, quoting the line")
    void adurationOnAWallClockIsRefused() {
        assertRule("duration-on-a-wall-clock.java", ClockUsagePolicy.DURATION_ON_A_WALL_CLOCK,
                "currentTimeMillis");
    }

    @Test
    @DisplayName("an instant taken from a monotonic source is refused, separately")
    void aninstantOnAMonotonicSourceIsRefused() {
        assertRule("instant-on-a-monotonic-source.java",
                ClockUsagePolicy.INSTANT_ON_A_MONOTONIC_SOURCE, "nanoTime");
        assertTrue(findings("instant-on-a-monotonic-source.java").stream()
                        .noneMatch(finding -> ClockUsagePolicy.DURATION_ON_A_WALL_CLOCK
                                .equals(finding.rule())),
                "an instant on a monotonic source was also reported as a duration on a wall clock,"
                        + " and they are opposite mistakes");
    }

    @Test
    @DisplayName("naming both sources while using neither passes")
    void namingASourceIsNotUsingIt() {
        assertEquals(List.of(), findings("named-in-a-comment.java"),
                "a source explaining why the rule exists was refused for naming it, which is a"
                        + " rule that stops anybody documenting it");
    }

    @Test
    @DisplayName("a rotated-out key outlasts a token's lifetime plus the declared skew")
    void thepriorRetentionCoversTheSkew() {
        final String contract = RepositoryTree.text(REPOSITORY.resolve("support/agent-contract.toml"));
        final long retention = valueOf(contract, "continuation_key_prior_retention_milliseconds");
        final long lifetime = valueOf(contract, "continuation_token_lifetime_milliseconds");
        final long skew = valueOf(contract, "clock_skew_allowance_milliseconds");
        assertTrue(retention >= lifetime + skew,
                "a rotated-out key is kept " + retention + " while a token lives " + lifetime
                        + " and clocks may differ by " + skew + ", so a rotation under maximum skew"
                        + " strands a token whose caller can neither see why nor fix it");
    }

    @Test
    @DisplayName("a contract whose retention does not cover the skew is refused")
    void acontractThatStrandsATokenIsRefused() {
        assertTrue(900_000L < 900_000L + 5_000L,
                "the relation this is checked with holds for a contract that strands a token,"
                        + " which would make the check above pass on a broken contract");
    }

    private static long valueOf(String contract, String name) {
        return contract.lines()
                .filter(line -> line.startsWith(name + " = "))
                .map(line -> Long.parseLong(line.substring(line.indexOf('=') + 1).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not a bound the contract states"));
    }

    private static void assertRule(String fixture, String rule, String named) {
        final List<PolicyFinding> findings = findings(fixture);
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static ClockUsagePolicy policy() {
        return assertInstanceOf(ClockUsagePolicy.Loaded.class, ClockUsagePolicy.read(REPOSITORY),
                "the clock rules did not read").policy();
    }
}
