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
 * The three ways a log line stops being findable, each refused as its own finding.
 *
 * <p>Three rather than one because they are three different mistakes, and a single finding covering
 * all of them would send three different people to read the same paragraph. The fourth test is the
 * one that keeps the other three usable: naming a refused form while explaining why it is refused
 * has to pass, or the rules quietly stop anybody from documenting them.</p>
 */
final class LogStatementPolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/log-statement");

    @Test
    @DisplayName("this repository writes every line through the one writer")
    void thisRepositoryWritesThroughOneWriter() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("a class reaching for a logger of its own is refused, naming the form it reached")
    void adirectLoggerIsRefused() {
        assertRule("direct-logger.java", LogStatementPolicy.DIRECT_LOGGER, "LoggerFactory");
    }

    @Test
    @DisplayName("a message somebody formatted is refused, separately from reaching for a logger")
    void aformattedMessageIsRefused() {
        final List<PolicyFinding> findings = findings("formatted-statement.java");
        assertRule("formatted-statement.java", LogStatementPolicy.FORMATTED_STATEMENT,
                "String.format");
        assertTrue(findings.stream()
                        .noneMatch(finding -> LogStatementPolicy.DIRECT_LOGGER
                                .equals(finding.rule())),
                "a formatted message was also reported as reaching for a logger, and the two are"
                        + " different mistakes made by different people: " + findings);
    }

    @Test
    @DisplayName("a statement carrying something the corpus plants is refused as its own finding")
    void acorpusValueIsRefused() {
        final List<PolicyFinding> findings = findings("corpus-interpolation.java");
        assertRule("corpus-interpolation.java", LogStatementPolicy.CORPUS_INTERPOLATION,
                "/var/slingshot-agent");
        assertTrue(findings.stream()
                        .noneMatch(finding -> LogStatementPolicy.DIRECT_LOGGER
                                .equals(finding.rule())),
                "the one that actually leaks was reported as one of the other two: " + findings);
    }

    @Test
    @DisplayName("naming all three forms in a comment while doing none of them passes")
    void namingAFormIsNotUsingIt() {
        assertEquals(List.of(), findings("named-in-a-comment.java"),
                "a source explaining why the forms are refused was refused for naming them, which"
                        + " is a rule that stops anybody documenting it");
    }

    @Test
    @DisplayName("the three rules are three, so a reader is sent to one paragraph rather than all")
    void thethreeRulesAreDistinct() {
        assertEquals(3, List.of(LogStatementPolicy.DIRECT_LOGGER,
                        LogStatementPolicy.FORMATTED_STATEMENT,
                        LogStatementPolicy.CORPUS_INTERPOLATION).stream().distinct().count(),
                "two of the three rules are spelled the same way");
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

    private static LogStatementPolicy policy() {
        return assertInstanceOf(LogStatementPolicy.Loaded.class,
                LogStatementPolicy.read(REPOSITORY),
                "the logging rules did not read").policy();
    }
}
