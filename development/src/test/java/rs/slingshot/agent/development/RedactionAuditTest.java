// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one place every way a secret could leave is checked at once.
 *
 * <p>Every rejection here is proved on a planted leak rather than by reading a passing repository:
 * a scan that has never caught anything is a scan nobody has watched work. So each of the eight
 * kinds is leaked deliberately, in each of the four places anything leaves from, and the audit is
 * required to name both.</p>
 */
final class RedactionAuditTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/redaction-audit");

    @Test
    @DisplayName("the committed corpus carries a planted value for every kind, and no ninth kind")
    void thecommittedCorpusIsClosed() {
        assertEquals(RedactionAudit.KINDS.size(), audit().corpus().size(),
                "the corpus and the kinds it is closed over disagree");
        RedactionAudit.KINDS.forEach(kind -> assertTrue(audit().corpus().stream()
                        .anyMatch(secret -> secret.kind().equals(kind)),
                kind + " is a kind nothing is planted for"));
        audit().corpus().forEach(secret -> {
            assertFalse(secret.planted().isBlank(), secret.kind() + " plants nothing");
            assertFalse(secret.heldWhere().isBlank(),
                    secret.kind() + " says nowhere this agent could hold one");
        });
    }

    @Test
    @DisplayName("a kind with nothing planted, and a planted value of no kind, are both refused")
    void bothWaysOfBreakingTheCorpusAreRefused() {
        assertInstanceOf(RedactionAudit.Refused.class,
                RedactionAudit.readCorpus(FIXTURES.resolve("kind-with-nothing-planted.toml")),
                "a kind nothing is planted for was accepted");
        assertInstanceOf(RedactionAudit.Refused.class,
                RedactionAudit.readCorpus(FIXTURES.resolve("planted-value-of-no-kind.toml")),
                "a planted value of a kind nobody declared was accepted");
    }

    @Test
    @DisplayName("every kind is caught in every place anything leaves from, named both ways")
    void everykindIsCaughtInEveryPlace() {
        audit().corpus().forEach(secret -> RedactionAudit.PLACES.forEach(place -> {
            final PolicyReport caught = audit().against(List.of(new RedactionAudit.Observation(
                    "capabilities", place, "answering with " + secret.planted() + " in it")));
            assertTrue(caught.render().contains("leaked-" + secret.kind()),
                    secret.kind() + " leaked in a " + place + " and nothing caught it: "
                            + caught.render());
            assertTrue(caught.render().contains(place),
                    "the finding does not say where it leaked from: " + caught.render());
        }));
    }

    @Test
    @DisplayName("a stream error, a reset, and a heartbeat are scanned like anything else")
    void thestreamIsScannedLikeAnythingElse() {
        final PolicyReport caught = audit().against(List.of(
                new RedactionAudit.Observation("events", "stream",
                        "event:error\ndata:{\"detail\":\"" + planted("repository-path") + "\"}"),
                new RedactionAudit.Observation("events", "stream",
                        "event:reset\ndata:{\"detail\":\"" + planted("internal-name") + "\"}"),
                new RedactionAudit.Observation("events", "stream",
                        ": alive " + planted("configuration-value"))));
        assertTrue(caught.render().contains("leaked-repository-path"), caught.render());
        assertTrue(caught.render().contains("leaked-internal-name"), caught.render());
        assertTrue(caught.render().contains("leaked-configuration-value"), caught.render());
        assertEquals(3, caught.render().lines().count(),
                "an error, a reset and a heartbeat did not produce one finding each: "
                        + caught.render());
    }

    @Test
    @DisplayName("a drive that skipped a route, a category, or a place is incomplete")
    void adriveThatSkippedAnythingIsIncomplete() {
        final List<RedactionAudit.Observation> partial = List.of(
                new RedactionAudit.Observation("capabilities", "body", "nothing secret"));
        final PolicyReport report = audit().completeness(partial, List.of("capabilities", "submit"),
                List.of("agent_unavailable"));
        assertTrue(report.render().contains("route-nothing-drove"), report.render());
        assertTrue(report.render().contains("submit"), report.render());
        assertTrue(report.render().contains("category-nothing-drove"), report.render());
        assertTrue(report.render().contains("place-nothing-observed"), report.render());
        assertEquals("", audit().completeness(complete(), List.of("capabilities"),
                        List.of("agent_unavailable")).render(),
                "a drive that covered everything was reported incomplete");
    }

    @Test
    @DisplayName("an observation from a place nothing leaves from is a defect in the drive")
    void anobservationFromNowhereIsAdefect() {
        final PolicyReport report = audit().against(List.of(
                new RedactionAudit.Observation("capabilities", "sideways", "nothing secret")));
        assertTrue(report.render().contains("unknown-place"), report.render());
    }

    private static List<RedactionAudit.Observation> complete() {
        return RedactionAudit.PLACES.stream()
                .map(place -> new RedactionAudit.Observation("capabilities", place,
                        "agent_unavailable and nothing secret"))
                .toList();
    }

    private static String planted(String kind) {
        return audit().corpus().stream()
                .filter(secret -> secret.kind().equals(kind))
                .findFirst()
                .orElseThrow()
                .planted();
    }

    private static RedactionAudit audit() {
        return assertInstanceOf(RedactionAudit.Loaded.class, RedactionAudit.read(REPOSITORY),
                "the committed corpus was refused").audit();
    }
}
