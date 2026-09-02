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
 * Where a stream is the right shape and where it is the wrong one.
 *
 * <p>The test that carries the argument is the inversion: the same two fixtures are read twice,
 * once with no row declaring them sensitive and once with one, and each is refused in exactly the
 * run where its shape is the wrong one. That is the whole point of declaring the paths rather than
 * picking a side.</p>
 */
final class AllocationPolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/allocation");

    @Test
    @DisplayName("this repository allocates the way its own policy declares")
    void thisRepositoryHoldsTheAllocationPolicy() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("an indexed loop outside a sensitive path is refused")
    void anIndexedLoopOutsideASensitivePathIsRefused() {
        assertRule(findings("indexed-loop.java"), "indexed-loop", "carrying");
    }

    @Test
    @DisplayName("a stream inside a sensitive path is refused")
    void aStreamInsideASensitivePathIsRefused() {
        assertRule(sensitiveFindings("stream-in-a-sensitive-path.java"),
                "stream-in-a-sensitive-path", "readUnsigned");
    }

    @Test
    @DisplayName("the rule inverts: each shape is refused on exactly one side of the line")
    void theRuleInvertsInsideADeclaredPath() {
        assertTrue(findings("stream-in-a-sensitive-path.java").isEmpty(),
                "a stream outside a sensitive path was refused");
        assertTrue(sensitiveFindings("indexed-loop.java").stream()
                        .noneMatch(finding -> "indexed-loop".equals(finding.rule())),
                "an indexed loop inside a sensitive path was refused");
    }

    @Test
    @DisplayName("text rebuilt on every turn of a loop is refused")
    void concatenationInALoopIsRefused() {
        assertRule(findings("concatenation-in-a-loop.java"), "concatenation-in-a-loop", "joined");
    }

    @Test
    @DisplayName("a copy of an immutable and a copy where a view would do are refused distinctly")
    void bothPointlessCopiesAreRefused() {
        assertRule(findings("copy-of-an-immutable.java"), "copy-of-an-immutable", "already");
        assertRule(findings("copy-where-a-view-would-do.java"), "copy-where-a-view-would-do",
                "an unmodifiable view of rows");
    }

    @Test
    @DisplayName("the accepted fixture passes every rule on both sides of the line")
    void theAcceptedFixturePasses() {
        assertEquals(List.of(), findings("accepted.java"));
    }

    @Test
    @DisplayName("every declared sensitive path exists, and one that does not is refused")
    void declaredPathsAreCheckedAgainstTheSource() {
        assertEquals("", policy().across(REPOSITORY).render());
        assertTrue(!policy().sensitivePaths().isEmpty(), "the policy declares no sensitive path");
        policy().sensitivePaths().forEach(path -> assertTrue(!path.reason().isBlank(),
                path.method() + " is declared sensitive and records no reason"));
        assertRule(policyAt("sensitive-path-that-does-not-exist.toml").across(REPOSITORY).findings(),
                "sensitive-path", "NothingLikeThis#readNothing");
    }

    @Test
    @DisplayName("a policy whose rule does not invert is refused, because declaring a path would do nothing")
    void aPolicyThatDoesNotInvertIsRefused() {
        assertInstanceOf(AllocationPolicy.Refused.class,
                AllocationPolicy.readPolicy(FIXTURES.resolve("rule-that-does-not-invert.toml")),
                "a policy in which the rule does not invert was accepted");
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static List<PolicyFinding> sensitiveFindings(String fixture) {
        return policyAt("declares-the-fixture-sensitive.toml").inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static AllocationPolicy policy() {
        return assertInstanceOf(AllocationPolicy.Loaded.class, AllocationPolicy.read(REPOSITORY),
                "the allocation policy was refused").policy();
    }

    private static AllocationPolicy policyAt(String fixture) {
        return assertInstanceOf(AllocationPolicy.Loaded.class,
                AllocationPolicy.readPolicy(FIXTURES.resolve(fixture)),
                fixture + " was refused").policy();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule())
                                && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }
}
