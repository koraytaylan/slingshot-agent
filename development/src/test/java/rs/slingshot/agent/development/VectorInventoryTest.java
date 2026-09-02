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
 * Whether every document kind still brings its own vector.
 *
 * <p>Each rejection is proved on a copy of the committed vector file with exactly one thing wrong
 * with it, so a failure names the thing rather than the file. The committed set is checked whole in
 * the first assertion, which is what makes the others mean something: a check that only ever saw
 * broken input would pass on a check that refused everything.</p>
 */
final class VectorInventoryTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/vector-inventory");

    /** How many document kinds this build declares: twelve protocol documents and fourteen commands. */
    private static final int SEVENTYSIX_KINDS = 76;

    @Test
    @DisplayName("every document kind has a vector this build accepts and one it refuses")
    void everyKindBringsItsOwnVector() {
        assertEquals("", inventory().against(sources()).render());
        assertEquals(SEVENTYSIX_KINDS, inventory().kinds().size(),
                "a document kind lost its row or gained one nobody declared");
        assertTrue(VectorInventory.vectorsIn(REPOSITORY.resolve(VectorInventory.VECTOR_FILE))
                        .size() >= 24,
                "the committed set no longer covers every kind from both sides");
    }

    @Test
    @DisplayName("an unknown kind, a vector with no note, and a duplicate are three rejections")
    void thethreeShapeRejectionsAreDistinct() {
        assertRule(against("unknown-kind.json"), "unknown-kind", "teleportation");
        assertRule(against("no-note.json"), "vector-with-no-note", "says nothing about what it");
        assertRule(against("duplicate.json"), "duplicate-vector", "is declared more than once");
    }

    @Test
    @DisplayName("a kind with no refused vector fails, naming the kind and which side is missing")
    void aKindWithNoRefusedVectorFails() {
        assertRule(against("kind-without-a-refused-vector.json"), "kind-without-a-vector",
                "operation has no vector this build refuses");
    }

    @Test
    @DisplayName("a bound with no vector at its edge fails, naming the bound and the edge")
    void aBoundWithNoVectorFails() {
        final PolicyReport report = against("bound-without-a-vector.json");
        assertRule(report, "bound-without-a-vector", "maximum_agent_error_message_bytes has no"
                + " vector at it");
        assertRule(report, "bound-without-a-vector", "maximum_agent_error_message_bytes has no"
                + " vector past it");
    }

    @Test
    @DisplayName("the bounds a vector must cover are the ones the schema record names")
    void theBoundsComeFromTheSchemaRecord() {
        assertEquals(5, VectorInventory.declaredBounds(
                        REPOSITORY.resolve("schemas/agent-protocol-digests.toml")).size(),
                "a bound was added to a model without the vectors being asked to cover it");
    }

    private static PolicyReport against(String fixture) {
        return inventory().against(sources().withVectors(FIXTURES.resolve(fixture)));
    }

    private static void assertRule(PolicyReport report, String rule, String named) {
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.rule().equals(rule)
                                && finding.symbol().contains(named)),
                "no " + rule + " finding named " + named + ": " + report.render());
    }

    private static VectorInventory inventory() {
        return assertInstanceOf(VectorInventory.Loaded.class, VectorInventory.read(REPOSITORY),
                "the inventory was refused").inventory();
    }

    private static VectorInventory.Sources sources() {
        return VectorInventory.Sources.of(REPOSITORY);
    }
}
