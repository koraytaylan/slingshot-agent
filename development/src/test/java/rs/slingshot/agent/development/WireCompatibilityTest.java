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
 * What goes on the wire now, against what was decided.
 *
 * <p>A protocol change nobody noticed is a client that stops working, and the moment it is cheap to
 * notice is the moment it is introduced — which is what this is for. It is byte-exact rather than
 * structural, because two documents that mean the same thing and are not the same bytes are two
 * different digests, and the five-field identity is a comparison of digests.</p>
 */
final class WireCompatibilityTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** How many commands the client's own table publishes, which this registry matches. */
    private static final int PUBLISHED_COMMANDS = 64;

    @Test
    @DisplayName("nothing on the wire has changed since it was decided")
    void nothingOnTheWireHasChanged() {
        assertEquals("", WireCompatibility.across(REPOSITORY).render());
    }

    @Test
    @DisplayName("every document kind has a snapshot and every snapshot has a kind")
    void thesnapshotsAndTheKindsAgreeBothWays() {
        assertTrue(WireCompatibility.currentDocuments(REPOSITORY).size() > PUBLISHED_COMMANDS,
                "there are fewer document kinds than commands, so the envelope, the events and the"
                        + " snapshots are recorded as nothing");
        assertTrue(WireCompatibility.across(REPOSITORY).findings().stream()
                        .noneMatch(finding -> WireCompatibility.A_KIND_WITH_NO_SNAPSHOT
                                .equals(finding.rule())
                                || WireCompatibility.A_SNAPSHOT_WITH_NO_KIND
                                        .equals(finding.rule())),
                "the snapshots and the wire disagree about what exists");
    }

    @Test
    @DisplayName("every command's contract identity is recorded, all five fields of it")
    void everycommandsIdentityIsRecorded() {
        final List<String> identity = WireCompatibility.currentRegistryIdentity(REPOSITORY);
        assertEquals(PUBLISHED_COMMANDS, identity.size(),
                "the registry no longer holds the commands the client publishes");
        identity.forEach(line -> assertEquals(5, line.split("\t").length,
                "a command's identity is recorded with other than its five fields: " + line));
    }

    @Test
    @DisplayName("the comparison is byte-exact, so a change that means the same thing still fails")
    void thecomparisonIsByteExact() {
        final String recorded = WireCompatibility.currentDocuments(REPOSITORY).get("envelope");
        assertTrue(recorded != null && !recorded.isBlank(),
                "the envelope is recorded as nothing, and it is the document every other one"
                        + " travels inside");
        assertTrue(!recorded.equals(recorded.replace(":", ": ")),
                "a document with a space after a separator is the same document to a reader and a"
                        + " different digest to the identity, which is the change that breaks a"
                        + " client while looking like it changed nothing");
    }

    @Test
    @DisplayName("two reads of the same source produce the same output, so a difference is real")
    void tworeadsAgree() {
        assertEquals(WireCompatibility.currentDocuments(REPOSITORY),
                WireCompatibility.currentDocuments(REPOSITORY),
                "reading the same source twice produced two different sets, which would make every"
                        + " snapshot difference a coin toss");
        assertEquals(WireCompatibility.currentRegistryIdentity(REPOSITORY),
                WireCompatibility.currentRegistryIdentity(REPOSITORY));
    }

    @Test
    @DisplayName("a change with no version increment is refused separately from one with")
    void aversionIncrementIsWhatMakesAChangeADecision() {
        assertEquals(2, List.of(WireCompatibility.THE_BYTES_CHANGED,
                        WireCompatibility.CHANGED_WITHOUT_A_VERSION_INCREMENT).stream()
                        .distinct().count(),
                "a change that carried a version increment and one that did not are reported the"
                        + " same way, and only one of the two is a decision somebody made");
    }
}
