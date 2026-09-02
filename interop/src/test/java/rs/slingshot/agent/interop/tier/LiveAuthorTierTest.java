// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.interop.tier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reaching somebody's real author, which never happens because a script ran.
 *
 * <p>One run against one author is evidence about that author and about nothing else, and the
 * report says so — because the thing that turns a useful observation into a false claim is somebody
 * quoting it later as evidence about a deployment row.</p>
 */
final class LiveAuthorTierTest {

    private static final Path REPOSITORY = repositoryRoot();

    /** How many commands the client's own table publishes, which this registry matches. */
    private static final int PUBLISHED_COMMANDS = 64;

    @Test
    @DisplayName("without the flag it refuses before it reads any configuration")
    void withoutTheFlagItRefusesFirst() {
        final LiveAuthorTier.Permission refused =
                LiveAuthorTier.permission(REPOSITORY, List.of());
        assertInstanceOf(LiveAuthorTier.Refused.class, refused,
                "a run against a real author was permitted with nobody asking for one");
        assertTrue(((LiveAuthorTier.Refused) refused).detail()
                        .contains(LiveAuthorTier.ENABLING_FLAG),
                "the refusal does not say what to type, which leaves somebody guessing at a"
                        + " command that reaches their author");
    }

    @Test
    @DisplayName("an instance nobody acknowledged is refused, naming what an operator must do")
    void anunacknowledgedInstanceIsRefused() {
        final LiveAuthorTier.Permission refused =
                LiveAuthorTier.permission(REPOSITORY, List.of(LiveAuthorTier.ENABLING_FLAG));
        assertInstanceOf(LiveAuthorTier.Refused.class, refused,
                "a run was permitted against an instance nobody acknowledged, and an address in a"
                        + " configuration file is not permission");
        assertTrue(((LiveAuthorTier.Refused) refused).detail().contains("acknowledged"),
                "the refusal does not say what an operator has to acknowledge");
    }

    @Test
    @DisplayName("what may run is the registry's read rows and nothing else")
    void whatMayRunIsDerivedFromTheRegistry() {
        final List<String> commands = LiveAuthorTier.commandsThatReplaceNothing(REPOSITORY);
        assertTrue(!commands.isEmpty(), "nothing may run, so this tier would prove nothing");
        assertTrue(commands.size() < PUBLISHED_COMMANDS,
                "every command may run against a real author, which means something that replaces"
                        + " content is reachable through this tier: " + commands.size());
        assertTrue(commands.stream().noneMatch(command -> command.startsWith("create_")
                        || command.startsWith("delete_") || command.startsWith("update_")
                        || command.startsWith("move_")),
                "a command that replaces something is reachable through this tier: " + commands);
    }

    @Test
    @DisplayName("the report says what it is, so nobody quotes it as something else")
    void thereportSaysWhatItIs() {
        final String report = LiveAuthorTier.report("https://author.example", "6.5.23",
                List.of("query_paths"));
        assertTrue(report.startsWith(LiveAuthorTier.WHAT_THIS_IS),
                "the report does not open by saying what it is: " + report);
        assertTrue(report.contains("6.5.23") && report.contains("query_paths"),
                "the report names neither the platform version nor the commands, which are the two"
                        + " things that say what it is an observation of: " + report);
        assertTrue(report.contains("not evidence about a deployment row"),
                "the report does not say what it is not, which is the sentence that stops it being"
                        + " quoted as a row's evidence");
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
