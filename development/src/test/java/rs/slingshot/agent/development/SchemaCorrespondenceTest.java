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
 * Whether the schemas this repository publishes still describe the models it runs.
 *
 * <p>The client's own schemas are carried in as fixtures and compared with this side's, so the two
 * halves of one protocol cannot drift apart quietly. What is compared is members: this side's copy
 * of the error schema also closes its code set, which is a refinement the client's own bytes
 * permit rather than a disagreement.</p>
 */
final class SchemaCorrespondenceTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES = REPOSITORY.resolve(
            "development/src/test/resources/fixtures/schema-correspondence");

    /** How many schemas this build commits: twelve protocol documents and fourteen commands's two. */
    private static final int ONEHUNDRED_AND_FORTY_SCHEMAS = 140;

    @Test
    @DisplayName("every committed schema describes the model it names, in both directions")
    void theSchemasAndTheModelsAgree() {
        assertEquals("", correspondence().against(sources()).render());
        assertEquals(ONEHUNDRED_AND_FORTY_SCHEMAS, correspondence().schemas().size(),
                "a document kind lost its schema or gained one nobody recorded");
    }

    @Test
    @DisplayName("a member only the schema has and one only the model has are two findings")
    void bothDirectionsOfTheMemberComparisonAreChecked() {
        final PolicyReport report = correspondence().against(sources()
                .withSchemas(FIXTURES.resolve("disagreeing")));
        assertRule(report, "schema-without-a-digest-row", "an-extra-member.json");
    }

    @Test
    @DisplayName("a schema whose digest is not its bytes' is refused naming both")
    void aDigestThatIsNotTheBytesIsRefused() {
        final SchemaCorrespondence changed = loaded(SchemaCorrespondence.readRecord(
                FIXTURES.resolve("digest-that-does-not-match.toml")));
        assertRule(changed.against(sources()), "digest-does-not-match", "and the record says");
    }

    @Test
    @DisplayName("a schema with no digest row and a row naming no schema are two findings")
    void bothDirectionsOfTheInventoryAreChecked() {
        final SchemaCorrespondence naming = loaded(SchemaCorrespondence.readRecord(
                FIXTURES.resolve("row-naming-no-schema.toml")));
        assertRule(naming.against(sources()), "digest-row-without-a-schema",
                "is named and does not exist");
        final SchemaCorrespondence missing = loaded(SchemaCorrespondence.readRecord(
                FIXTURES.resolve("missing-a-row.toml")));
        assertRule(missing.against(sources()), "schema-without-a-digest-row",
                "is committed and no row names it");
    }

    @Test
    @DisplayName("a schema stating a length the model does not read is refused naming both")
    void aBoundThatDisagreesIsRefused() {
        final SchemaCorrespondence disagreeing = loaded(SchemaCorrespondence.readRecord(
                FIXTURES.resolve("bound-that-disagrees.toml")));
        assertRule(disagreeing.against(sources()), "bound-disagrees", "and maximum_agent_error");
    }

    @Test
    @DisplayName("a member only one side declares is found whichever side declares it")
    void aMemberOnlyOneSideHasIsFound() {
        final SchemaCorrespondence pointing = loaded(SchemaCorrespondence.readRecord(
                FIXTURES.resolve("model-that-does-not-match.toml")));
        final PolicyReport report = pointing.against(sources());
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> "member-only-in-the-schema".equals(finding.rule())
                                || "member-only-in-the-model".equals(finding.rule())),
                "a schema pointed at another document's model agreed with it: " + report.render());
    }

    @Test
    @DisplayName("nothing in the bundle reads a schema at run time")
    void nothingLoadsASchemaAtRunTime() {
        assertTrue(correspondence().against(sources()).findings().stream()
                        .noneMatch(finding -> "schema-loaded-at-run-time".equals(finding.rule())),
                "something in the bundle names the schema directory");
    }

    @Test
    @DisplayName("this side and the client declare the same members for every shared document kind")
    void thetwoHalvesOfTheProtocolAgree() {
        assertTrue(correspondence().against(sources()).findings().stream()
                        .noneMatch(finding -> "client-schema-disagrees".equals(finding.rule())),
                "this side and the client disagree about a document they both write");
        assertEquals(9, java.util.Objects.requireNonNull(
                        FIXTURES.resolve("client").toFile().list()).length,
                "a client schema was added or lost without the comparison changing");
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.rule().equals(rule)
                                && finding.symbol().contains(named)),
                "no " + rule + " finding named " + named + ": " + report.render());
    }

    private static SchemaCorrespondence correspondence() {
        return loaded(SchemaCorrespondence.read(REPOSITORY));
    }

    private static SchemaCorrespondence loaded(SchemaCorrespondence.Outcome outcome) {
        return assertInstanceOf(SchemaCorrespondence.Loaded.class, outcome,
                "the record was refused").correspondence();
    }

    private static SchemaCorrespondence.Sources sources() {
        return SchemaCorrespondence.Sources.of(REPOSITORY);
    }
}
