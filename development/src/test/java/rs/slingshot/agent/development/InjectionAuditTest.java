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
 * Whether a caller's value can reach a grammar, and whether the corpus that drives it is closed.
 *
 * <p>The strongest answer this product has to query injection is that it has no query. Every search
 * walks resources through the caller's own resolver, so there is no statement for a value to break
 * out of — and the point of the audit is to keep that true rather than to record that it was true
 * once.</p>
 */
final class InjectionAuditTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/injection");

    @Test
    @DisplayName("nothing in this product reaches a query engine or writes an unescaped name")
    void thisProductHasNoGrammarToBreakInto() {
        assertEquals("", audit().across(REPOSITORY).render());
    }

    @Test
    @DisplayName("a statement built out of a caller's value is refused, as two findings")
    void aconcatenatedQueryIsRefused() {
        final List<PolicyFinding> findings = findings("query-by-concatenation.java");
        assertTrue(findings.stream()
                        .anyMatch(finding -> InjectionAudit.A_QUERY_ENGINE_IS_REACHED
                                .equals(finding.rule())),
                "reaching a query engine was accepted: " + findings);
        assertTrue(findings.stream()
                        .anyMatch(finding -> InjectionAudit.QUERY_BY_CONCATENATION
                                .equals(finding.rule())),
                "a statement built out of pieces was accepted, and the pieces are a caller's: "
                        + findings);
    }

    @Test
    @DisplayName("a caller's value written as a node name is refused")
    void anunescapedNameIsRefused() {
        assertTrue(findings("unescaped-repository-name.java").stream()
                        .anyMatch(finding -> InjectionAudit.UNESCAPED_REPOSITORY_NAME
                                .equals(finding.rule())),
                "a caller's value became a node name, and a colon in one chooses a namespace");
    }

    @Test
    @DisplayName("naming the refused calls while making neither passes")
    void namingACallIsNotMakingIt() {
        assertEquals(List.of(), findings("named-in-a-comment.java"),
                "a source explaining why there are no queries was refused for saying so");
    }

    @Test
    @DisplayName("every grammar has a shape and every shape names a grammar")
    void thecorpusIsClosedBothWays() {
        assertEquals("", audit().coverage().render());
        assertTrue(audit().shapes().size() >= InjectionAudit.GRAMMARS.size(),
                "there are fewer shapes than grammars, so a grammar is attacked by nothing");
        assertTrue(audit().shapes().stream()
                        .anyMatch(shape -> "%252e%252e%252f".equals(shape.value())),
                "the doubly-encoded traversal is not in the corpus, and it is the one that gets"
                        + " past the fix somebody applies to the singly-encoded one");
    }

    private static List<PolicyFinding> findings(String fixture) {
        return audit().inFile(fixture, FIXTURES.resolve(fixture));
    }

    private static InjectionAudit audit() {
        return assertInstanceOf(InjectionAudit.Loaded.class, InjectionAudit.read(REPOSITORY),
                "the injection corpus did not read").audit();
    }
}
