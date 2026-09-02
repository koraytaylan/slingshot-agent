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
 * The rules about this repository's source, each proved against three fixtures.
 *
 * <p>The third fixture is the one that decides whether the rule is real: a file naming every
 * forbidden construct only inside a comment or a string literal. A checker that matched text would
 * refuse it, and would therefore refuse the document that explains the rule — which is how a source
 * policy ends up with an escape hatch nobody meant to add.</p>
 */
final class SourcePolicyTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/source-policy");

    @Test
    @DisplayName("the counter mixin is refused anywhere, and the reason is recorded beside it")
    void theAtomicCounterMixinIsRefusedAnywhere() {
        // The symbols themselves are not spelled here. A suite that wrote them out would be a
        // file carrying exactly what the rule refuses, and the rule would refuse the suite.
        final SourcePolicy policy = policy();
        assertEquals(2, policy.refusedSymbolsDeclared().size(),
                "the symbols this repository refuses changed without the reason changing");
        assertEquals("", policy.refusedSymbols(REPOSITORY).render(),
                "something in this repository counts the way the policy refuses to count");
        final PolicyReport found = policy.refusedSymbolsIn(
                FIXTURES.resolve("refused-symbol.java"), "refused-symbol.java");
        assertEquals(policy.refusedSymbolsDeclared().getFirst(),
                found.findings().getFirst().symbol(),
                "a fixture counting the refused way was not refused: " + found.render());
    }

    @Test
    @DisplayName("this repository holds every rule the source policy decides")
    void thisRepositoryHoldsTheSourcePolicy() {
        assertEquals("", policy().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("the accepted fixture passes every rule")
    void theAcceptedFixturePasses() {
        assertEquals(List.of(), findings("accepted.java"));
    }

    @Test
    @DisplayName("an abbreviated name is refused wherever it is declared")
    void anAbbreviatedNameIsRefused() {
        assertRule(findings("abbreviated-name.java"), "abbreviated-name", "reqCount spells req short");
    }

    @Test
    @DisplayName("a single-character name is refused wherever it is declared")
    void aSingleCharacterNameIsRefused() {
        assertRule(findings("single-character-name.java"), "single-character-name", "n");
    }

    @Test
    @DisplayName("a quantity nobody named is refused, and the named constant beside it is not")
    void aMagicNumberIsRefused() {
        assertRule(findings("magic-number.java"), "magic-number", "7");
        assertTrue(findings("accepted.java").isEmpty(),
                "a value that already carries a name was refused");
    }

    @Test
    @DisplayName("the second-declaration rule catches both the literal and the named form")
    void theSecondDeclarationRuleCatchesBothForms() {
        assertRule(findings("second-declaration-literal.java"), "second-declaration",
                "8192 is the contract's own maximum_author_response_header_bytes");
        assertRule(findings("second-declaration-name.java"), "second-declaration",
                "MAXIMUM_ROUTE_QUERY_BYTES is named after the contract's maximum_route_query_bytes");
    }

    @Test
    @DisplayName("a constant whose name states no bound is not a second declaration of one")
    void aConstantStatingNoBoundIsNotASecondDeclaration() {
        assertEquals(List.of(), findings("structural-constant.java").stream()
                        .filter(finding -> "second-declaration".equals(finding.rule()))
                        .toList(),
                "a milliseconds-in-a-second conversion and a read buffer were called restatements"
                        + " of the bounds whose values they happen to equal");
    }

    @Test
    @DisplayName("the rule does not fire inside the package that owns the contract")
    void theRuleDoesNotFireInsideTheContractPackage() {
        final Path contract = REPOSITORY.resolve(
                "core/src/main/java/rs/slingshot/agent/contract/AgentContract.java");
        assertEquals(List.of(), policy().inFile("AgentContract.java", contract, SourcePolicy.Tree.MAIN)
                .stream()
                .filter(finding -> "second-declaration".equals(finding.rule()))
                .toList());
    }

    @Test
    @DisplayName("a file naming every forbidden construct only in a comment or a literal passes")
    void namingAForbiddenConstructIsNotDeclaringOne() {
        assertEquals(List.of(), findings("forbidden-things-in-comments-and-strings.java"));
    }

    @Test
    @DisplayName("the line ceiling holds at exactly one thousand lines and refuses one more")
    void theLineCeilingHoldsAtBothSides() {
        assertEquals(List.of(), findings("at-the-ceiling.java"));
        assertRule(findings("one-line-over.java"), "file-length", "1001 physical lines");
    }

    @Test
    @DisplayName("no complexity or nesting ceiling is declared in this policy")
    void noComplexityCeilingIsDeclaredHere() {
        final PolicyDocument.Outcome outcome =
                PolicyDocument.load(REPOSITORY.resolve("policy/source-policy.toml"),
                        SourcePolicy.shape());
        final PolicyDocument document = assertInstanceOf(PolicyDocument.Loaded.class, outcome,
                "the source policy was refused").document();
        document.keys().forEach(key -> {
            assertTrue(!key.contains("complexity"),
                    "the source policy declares " + key + ", which the method-shape policy owns");
            assertTrue(!key.contains("nesting"),
                    "the source policy declares " + key + ", which the method-shape policy owns");
        });
    }

    @Test
    @DisplayName("output ordering is identical across two runs over a corpus with several files")
    void orderingIsIdenticalAcrossTwoRuns() {
        final PolicyReport first = corpus();
        final PolicyReport second = corpus();
        assertEquals(first.render(), second.render());
        assertTrue(!first.isEmpty(), "the corpus produced no findings to order");
        final List<String> files = first.findings().stream().map(PolicyFinding::file).toList();
        assertEquals(files.stream().sorted().toList(), files, "the report is not ordered by file");
    }

    @Test
    @DisplayName("the checker does not restate a question the policy leaves to a reader")
    void theChecklistIsNotARuleInDisguise() {
        assertEquals("", policy().reviewChecklist().render());
        assertTrue(policy().reviewQuestions().size() >= SourcePolicy.RULES.size() - 1,
                "the policy records fewer questions than the checker has rules");
        assertNotEmpty(policyAt("policy-restating-a-rule.toml").reviewChecklist());
    }

    @Test
    @DisplayName("the abbreviation list is the sibling's own closed list")
    void theAbbreviationListIsClosed() {
        final List<String> abbreviations = policy().abbreviations();
        assertTrue(abbreviations.contains("req"), "the list does not hold req");
        assertTrue(abbreviations.contains("impl"), "the list does not hold impl");
        assertTrue(!abbreviations.contains("request"), "the list refuses a word spelled in full");
    }

    private static PolicyReport corpus() {
        return PolicyReport.of(List.of("abbreviated-name.java", "magic-number.java",
                        "second-declaration-literal.java", "single-character-name.java").stream()
                .flatMap(fixture -> findings(fixture).stream())
                .toList());
    }

    private static List<PolicyFinding> findings(String fixture) {
        return policy().inFile(fixture, FIXTURES.resolve(fixture), SourcePolicy.Tree.MAIN);
    }

    private static SourcePolicy policy() {
        return assertInstanceOf(SourcePolicy.Loaded.class, SourcePolicy.read(REPOSITORY),
                "the source policy was refused").policy();
    }

    private static SourcePolicy policyAt(String fixture) {
        final Path directory = FIXTURES.resolve(fixture).getParent();
        assertTrue(directory != null, "the fixture has no directory");
        return assertInstanceOf(SourcePolicy.Loaded.class,
                SourcePolicy.readPolicy(FIXTURES.resolve(fixture), REPOSITORY),
                fixture + " was refused").policy();
    }

    private static void assertRule(List<PolicyFinding> findings, String rule, String named) {
        assertTrue(!findings.isEmpty(), "the rule accepted what it must refuse");
        assertTrue(findings.stream()
                        .anyMatch(finding -> rule.equals(finding.rule()) && finding.symbol().contains(named)),
                "no " + rule + " finding names " + named + ": " + findings);
    }

    private static void assertNotEmpty(PolicyReport report) {
        assertTrue(!report.isEmpty(), "the rule accepted what it must refuse");
    }
}
