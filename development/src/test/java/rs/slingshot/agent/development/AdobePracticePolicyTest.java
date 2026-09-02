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
 * The practices that decide whether this survives the next upgrade.
 *
 * <p>The deprecation list is the part that cannot rot: every row is checked against the platform
 * artifact the build actually resolved, so a row naming something the platform does not declare
 * deprecated fails, and the list can never become a place to record opinions.</p>
 */
final class AdobePracticePolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/adobe-practice");

    @Test
    @DisplayName("this repository holds every lifecycle practice")
    void thisRepositoryHoldsThePractices() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("a resolver closed in a trailing block an early return skips is refused")
    void anUnclosedResolverIsRefused() {
        assertRule(findings("unclosed-resolver.java"), "unclosed-resolver", "resolver");
        assertEquals(List.of(), findings("closed-resolver.java"),
                "a resolver the language closes on every path was refused");
    }

    @Test
    @DisplayName("mutable state, a manual lookup, and synchronisation are three distinct refusals")
    void theThreeComponentRefusalsAreDistinct() {
        assertRule(findings("component-mutable-state.java"), "component-mutable-state", "lastPath");
        assertRule(findings("component-manual-lookup.java"), "component-manual-lookup", "getService");
        assertRule(findings("component-synchronisation.java"), "component-synchronisation",
                "ComponentSynchronisation");
    }

    @Test
    @DisplayName("a deprecated member is refused with its replacement named")
    void aDeprecatedMemberIsRefusedNamingItsReplacement() {
        assertRule(findings("deprecated-member.java"), "deprecated-member",
                "getAdministrativeResourceResolver is deprecated; use getServiceResourceResolver");
    }

    @Test
    @DisplayName("every rule decides on parsed structure rather than on text")
    void everyRuleDecidesOnStructure() {
        assertEquals(List.of(), findings("accepted.java"),
                "a file naming the refused calls in prose and in a literal was refused");
    }

    @Test
    @DisplayName("every declared deprecation is one the platform itself declares")
    void theDeprecationListIsCheckedAgainstThePlatform() {
        assertEquals("", policy().againstThePlatform(ReactorModel.at(REPOSITORY)).render());
        assertTrue(!policy().deprecatedMembers().isEmpty(), "the policy refuses nothing at all");
        policy().deprecatedMembers().forEach(row -> {
            assertTrue(!row.replacement().isBlank(), row.member() + " names no replacement");
            assertTrue(!row.reason().isBlank(), row.member() + " records no reason");
        });
    }

    @Test
    @DisplayName("a row naming something the platform does not declare deprecated is refused")
    void aRowThePlatformDoesNotDeclareIsRefused() {
        assertRule(policyAt("row-the-platform-does-not-declare.toml")
                        .againstThePlatform(ReactorModel.at(REPOSITORY)).findings(),
                "deprecation-list", "ResourceResolver#getResource");
    }

    @Test
    @DisplayName("a row refusing something with no replacement named refuses the whole policy")
    void aRowWithNoReplacementRefusesThePolicy() {
        assertInstanceOf(AdobePracticePolicy.Refused.class,
                AdobePracticePolicy.readPolicy(FIXTURES.resolve("row-with-no-replacement.toml")),
                "a deprecation refused with no replacement was accepted");
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static AdobePracticePolicy policy() {
        return assertInstanceOf(AdobePracticePolicy.Loaded.class, AdobePracticePolicy.read(REPOSITORY),
                "the practice policy was refused").policy();
    }

    private static AdobePracticePolicy policyAt(String fixture) {
        return assertInstanceOf(AdobePracticePolicy.Loaded.class,
                AdobePracticePolicy.readPolicy(FIXTURES.resolve(fixture)),
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
