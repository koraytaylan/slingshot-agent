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
 * Everywhere a value can leave, and whether anything is planted where it would leave from.
 *
 * <p>Two audits already exist for the two places anybody thinks of. What this adds is the four that
 * leave without anybody watching — a log line, an event, a stored artifact, and a property the
 * agent itself wrote — and the rule that a kind nothing holds is a kind reporting clean because it
 * was never in the room.</p>
 */
final class ExposureSurfaceTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** How many commands the client's own table publishes, which this registry matches. */
    private static final int PUBLISHED_COMMANDS = 64;

    /** How many kinds the corpus declares, which is a closed set. */
    private static final int CORPUS_KINDS = 8;

    @Test
    @DisplayName("every place a value leaves is driven, and every kind is held somewhere")
    void everyplaceIsDrivenAndEveryKindIsHeld() {
        assertEquals("", ExposureSurface.across(REPOSITORY).render());
    }

    @Test
    @DisplayName("the four places nobody watches are audited beside the two everybody does")
    void thefourUnwatchedPlacesAreAudited() {
        assertTrue(ExposureSurface.PLACES.containsAll(List.of("log", "stream", "artifact",
                        "agent-written-property")),
                "one of the four places a value leaves without anybody watching is not audited: "
                        + ExposureSurface.PLACES);
        assertTrue(ExposureSurface.PLACES.containsAll(RedactionAudit.PLACES),
                "the route audit scans somewhere this one does not, so a value could leave through"
                        + " a place only one of the two knows about");
    }

    @Test
    @DisplayName("every corpus kind has somewhere it is planted")
    void everykindIsPlantedSomewhere() {
        final List<String> kinds = ExposureSurface.kindsIn(REPOSITORY);
        assertEquals(CORPUS_KINDS, kinds.size(),
                "the corpus is no longer the closed set it declares itself to be: " + kinds);
        kinds.forEach(kind -> assertTrue(
                ExposureSurface.HOLDERS.contains(ExposureSurface.holderFor(kind)),
                kind + " is planted nowhere the surface passes through, so scanning for it proves"
                        + " nothing"));
    }

    @Test
    @DisplayName("the key ring is a kind of its own, planted in the one place it can be")
    void thekeyRingIsItsOwnKind() {
        assertEquals("the key ring", ExposureSurface.holderFor("key"),
                "the signing key is planted somewhere other than the ring, and the ring is the"
                        + " one place it actually lives");
    }

    @Test
    @DisplayName("the surface is derived from the table, the registry and the checks")
    void thesurfaceIsDerivedRatherThanListed() {
        final ExposureSurface.Surface surface = ExposureSurface.of(REPOSITORY);
        assertEquals(PUBLISHED_COMMANDS, surface.commands().size(),
                "a command is either undriven or driven and unregistered");
        assertTrue(!surface.routes().isEmpty(), "no route is driven");
        assertEquals(6, surface.healthChecks().size(),
                "the health checks this audit drives are no longer the six this agent publishes: "
                        + surface.healthChecks());
    }
}
