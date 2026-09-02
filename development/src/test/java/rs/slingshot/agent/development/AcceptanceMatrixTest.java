// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What actually ran against each deployment row, and what may therefore be claimed about it.
 *
 * <p>A row does not become supported because the code compiled. Until a tier has run against it,
 * it is declared and unproved — which is a more useful thing to publish than a claim nobody tested
 * and a less embarrassing one than a claim somebody finds out is false.</p>
 */
final class AcceptanceMatrixTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/acceptance-matrix");

    @Test
    @DisplayName("this matrix names every deployment row and nothing that is not one")
    void thematrixAndTheDeploymentsAgreeBothWays() {
        assertEquals("", matrix().against(REPOSITORY).render());
    }

    @Test
    @DisplayName("nothing has run against either row, and the matrix says so rather than claiming")
    void nothingIsProvedYetAndItSaysSo() {
        matrix().entries().forEach(entry -> assertEquals(
                AcceptanceMatrix.Standing.DECLARED_AND_UNPROVED, entry.standing(),
                entry.deployment() + " is recorded as proved and nothing has run against it"));
    }

    @Test
    @DisplayName("a row with complete evidence is proved and one with none is not")
    void completeEvidenceIsWhatProvesARow() {
        matrixAt("complete-evidence.toml").entries().forEach(entry -> assertEquals(
                AcceptanceMatrix.Standing.PROVED, entry.standing(),
                entry.deployment() + " has a tier, an instance, scenarios and an observed stream"
                        + " and is still not proved"));
        matrixAt("no-evidence.toml").entries().forEach(entry -> assertEquals(
                AcceptanceMatrix.Standing.DECLARED_AND_UNPROVED, entry.standing(),
                entry.deployment() + " has no evidence at all and is recorded as proved"));
    }

    @Test
    @DisplayName("a row whose own ingress was never watched is unproved however well it works here")
    void unobservedStreamingIsNotProved() {
        matrixAt("streaming-unobserved.toml").entries().stream()
                .filter(entry -> "aem-cloud-service".equals(entry.deployment()))
                .forEach(entry -> assertEquals(AcceptanceMatrix.Standing.DECLARED_AND_UNPROVED,
                        entry.standing(),
                        "a row whose own ingress has never been watched passing a stream is"
                                + " recorded as proved, and what is between a client and this agent"
                                + " on that row is not something this repository can test"));
        assertTrue(!matrixAt("streaming-unobserved.toml").claimOf("aem-cloud-service").findings()
                        .isEmpty(),
                "a release claiming that row as supported was permitted");
    }

    @Test
    @DisplayName("an unknown scenario, tier, and row are three distinct refusals")
    void thethreeUnknownReferencesAreThree() {
        assertRule("unknown-scenario.toml", AcceptanceMatrix.AN_UNKNOWN_SCENARIO);
        assertRule("unknown-tier.toml", AcceptanceMatrix.AN_UNKNOWN_TIER);
        assertRule("unknown-row.toml", AcceptanceMatrix.AN_ENTRY_WITH_NO_ROW);
        assertRule("unknown-row.toml", AcceptanceMatrix.A_ROW_WITH_NO_ENTRY);
    }

    @Test
    @DisplayName("a release claiming an unproved row as supported is refused, naming the row")
    void areleaseCannotClaimAnUnprovedRow() {
        assertTrue(matrix().claimOf("aem-cloud-service").findings().stream()
                        .anyMatch(finding -> AcceptanceMatrix.CLAIMED_WITHOUT_EVIDENCE
                                .equals(finding.rule())),
                "a row nothing has run against was claimable as supported");
        assertEquals("", matrixAt("complete-evidence.toml").claimOf("aem-cloud-service").render(),
                "a row with complete evidence was refused as a claim, which would make the matrix"
                        + " useless for the one thing it is for");
    }

    private static void assertRule(String fixture, String rule) {
        assertTrue(matrixAt(fixture).against(REPOSITORY).findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule())),
                fixture + " was not refused under " + rule + ": "
                        + matrixAt(fixture).against(REPOSITORY).render());
    }

    private static AcceptanceMatrix matrixAt(String fixture) {
        return assertInstanceOf(AcceptanceMatrix.Loaded.class,
                AcceptanceMatrix.readFile(FIXTURES.resolve(fixture)),
                fixture + " did not read").matrix();
    }

    private static AcceptanceMatrix matrix() {
        return assertInstanceOf(AcceptanceMatrix.Loaded.class, AcceptanceMatrix.read(REPOSITORY),
                "the acceptance matrix did not read").matrix();
    }
}
