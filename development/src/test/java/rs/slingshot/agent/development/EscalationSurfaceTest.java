// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every direction a caller could go that they should not, derived rather than imagined.
 *
 * <p>The directions that seem likely are the ones that were already thought about. These come from
 * the architecture: a caller reaching content through a command they could not reach directly, a
 * caller reaching the agent's own state, a handler obtaining a session other than the request's, a
 * command running as anybody but the requesting user, and a read growing into a write through the
 * room it was given to work in.</p>
 *
 * <p>The strongest of them is the one that needs no guard at all: there is no impersonation call
 * anywhere in either bundle, so there is no path by which a command runs as somebody else. A guard
 * can be got round; an absence cannot.</p>
 */
final class EscalationSurfaceTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** How many commands the client's own table publishes, which this registry matches. */
    private static final int PUBLISHED_COMMANDS = 64;

    @Test
    @DisplayName("no way to run as somebody else exists, and every access class matches behaviour")
    void nothingHereCanRunAsSomebodyElse() {
        assertEquals("", EscalationSurface.across(REPOSITORY).render());
    }

    @Test
    @DisplayName("the surface is derived from the table, the registry and the package")
    void thesurfaceIsDerivedRatherThanListed() {
        final EscalationSurface.Surface surface = EscalationSurface.of(REPOSITORY);
        assertEquals(PUBLISHED_COMMANDS, surface.commands().size(),
                "the registry no longer holds the commands the client publishes, so a command is"
                        + " either unattacked or attacked and unregistered");
        assertTrue(!surface.routes().isEmpty(), "no route is on the surface");
        assertTrue(!surface.consoleResources().isEmpty(), "no console resource is on the surface");
        assertTrue(surface.size() > PUBLISHED_COMMANDS,
                "the surface is the commands alone, and a route or a console resource added later"
                        + " would not be attacked");
    }

    @Test
    @DisplayName("every read declares read and every write declares write, checked against both")
    void everyaccessClassMatchesWhatItDoes() {
        final long reads = EscalationSurface.rowsIn(REPOSITORY).stream()
                .filter(row -> "read".equals(row.accessClass()))
                .count();
        final long writes = EscalationSurface.rowsIn(REPOSITORY).stream()
                .filter(row -> "write".equals(row.accessClass()))
                .count();
        assertEquals(PUBLISHED_COMMANDS, reads + writes,
                "a row declares an access class that is neither, so it is held to neither rule");
        assertTrue(reads > 0 && writes > 0,
                "every command is one kind, which would make one of the two rules vacuous");
    }

    @Test
    @DisplayName("the one command with room to work is a read, and it obtains no session")
    void theroomToWorkIsGivenToAReadThatObtainsNothing() {
        final long staging = EscalationSurface.rowsIn(REPOSITORY).stream()
                .filter(row -> row.stagingBytes() > 0)
                .count();
        assertTrue(staging <= 1,
                "more than one command has room of its own inside the agent's tree, and a scratch"
                        + " directory is exactly where a read grows into a write: " + staging);
        assertTrue(EscalationSurface.across(REPOSITORY).findings().stream()
                        .noneMatch(finding -> EscalationSurface.A_STAGING_READ_OBTAINS_A_SESSION
                                .equals(finding.rule())),
                "a command with room to work obtains a session");
    }

    @Test
    @DisplayName("a command's handler is found from its wire name rather than from a list")
    void ahandlerIsFoundFromItsWireName() {
        assertEquals("CreatePageHandler.java", EscalationSurface.handlerName("create_page"));
        assertEquals("QueryPathsHandler.java", EscalationSurface.handlerName("query_paths"));
    }
}
