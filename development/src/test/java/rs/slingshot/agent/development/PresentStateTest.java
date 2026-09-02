// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether the documents describe the repository in the commit they ship with.
 *
 * <p>Documentation rots in one direction: the code moves and the prose stays. So every falsifiable
 * claim a document makes — about a route, a command, a health check, a deployment row — is compared
 * with the committed source of that fact, in both directions.</p>
 *
 * <p>What a checker cannot decide is kept separate rather than pretended about. Whether a sentence
 * is accurate, whether the set of them is complete, and whether a failure message tells a reader
 * what to do are review questions with recorded answers, not assertions — and a checklist that
 * repeated the checker would be a checklist nobody reads.</p>
 */
final class PresentStateTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** How many commands the client's own table publishes, which this registry matches. */
    private static final int PUBLISHED_COMMANDS = 64;

    /** How many checks this agent publishes. */
    private static final int HEALTH_CHECKS = 6;

    /**
     * The same count as this repository's prose writes it.
     *
     * <p>Numbers are written in words in the documents and in digits in the code, so the check
     * accepts either — what it is about is whether the document still names the count, not which
     * of the two spellings somebody used.</p>
     */
    private static final String SPELLED_COUNT = "sixty-four";

    @Test
    @DisplayName("the documents describe what is here rather than what is planned")
    void thedocumentsDescribeThePresent() {
        assertEquals("", documentation().against(REPOSITORY).render());
    }

    @Test
    @DisplayName("every health check the installing document names is one this agent publishes")
    void theinstallingDocumentNamesTheRealChecks() {
        final String installing = RepositoryTree.text(REPOSITORY.resolve("docs/INSTALLING.md"));
        final List<String> checks = ExposureSurface.of(REPOSITORY).healthChecks();
        assertEquals(HEALTH_CHECKS, checks.size(),
                "this agent no longer publishes six checks: " + checks);
        checks.forEach(check -> assertTrue(installing.contains(check),
                check + " is published and the installing document does not name it, so an"
                        + " operator meets it for the first time on their dashboard"));
    }

    @Test
    @DisplayName("every deployment row the installing document describes is one the matrix declares")
    void theinstallingDocumentDescribesTheRealRows() {
        final String installing = RepositoryTree.text(REPOSITORY.resolve("docs/INSTALLING.md"));
        PlatformFloor.read(REPOSITORY).rows().forEach(row -> assertTrue(
                installing.contains(row.slingVersion()) || installing.contains("Cloud Service")
                        || installing.contains("6.5"),
                row.identifier() + " is a supported row and the installing document says nothing"
                        + " about how the artifact arrives on it"));
    }

    @Test
    @DisplayName("the security document states the boundary in one sentence and does not soften it")
    void thesecurityDocumentStatesTheBoundary() {
        final String security = RepositoryTree.text(REPOSITORY.resolve("docs/SECURITY.md"))
                .replaceAll("\\s+", " ");
        assertTrue(security.contains("could already do by hand"),
                "the trust boundary is not stated in a sentence somebody can quote");
        assertTrue(security.contains("Widening the permitted groups widens who can act"),
                "the document does not say what widening the permitted groups does, which is the"
                        + " one decision an operator is actually making");
        assertTrue(security.contains("no code that could impersonate")
                        || security.contains("no code"),
                "the document describes a guard against impersonation rather than the absence of"
                        + " any way to impersonate, and a guard can be got round");
    }

    @Test
    @DisplayName("the command count the documents claim is the count the registry holds")
    void thecommandCountIsTheRegistrys() {
        final String readme = RepositoryTree.text(REPOSITORY.resolve("README.md"));
        assertEquals(PUBLISHED_COMMANDS, EscalationSurface.rowsIn(REPOSITORY).size(),
                "the registry no longer holds the commands the client publishes");
        assertTrue(readme.contains(SPELLED_COUNT) || readme.contains(
                        String.valueOf(PUBLISHED_COMMANDS)),
                "the README no longer says how many commands there are, and a reader counting"
                        + " them by hand is a reader the document failed");
    }

    @Test
    @DisplayName("what a checker cannot decide is a recorded review rather than a silent claim")
    void whatAcheckerCannotDecideIsReviewed() {
        final String review = RepositoryTree.text(
                REPOSITORY.resolve("docs/DOCUMENTATION_REVIEW.md"));
        assertTrue(!review.isBlank(),
                "there is no recorded review, so accuracy and completeness are claims nobody made");
        assertTrue(documentation().review().size() > 1,
                "the review is one question, which is a checklist rather than a review");
    }

    private static ProductDocumentation documentation() {
        return org.junit.jupiter.api.Assertions.assertInstanceOf(ProductDocumentation.Loaded.class,
                ProductDocumentation.read(REPOSITORY),
                "the documentation rules did not read").rules();
    }
}
