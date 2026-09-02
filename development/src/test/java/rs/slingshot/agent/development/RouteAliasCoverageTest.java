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
 * Whether the second paths this side carries are the ones the client actually asks for.
 *
 * <p>Both directions matter and hide different mistakes: a client constant nothing answers is a
 * client that cannot talk to this agent, and an alias nothing asks for is a path in {@code /libs}
 * that outlived its reason. Every rejection here is proved on a fixture rather than by reading the
 * committed tree, because a check that only ever sees a passing repository is one nobody has
 * watched refuse anything.</p>
 */
final class RouteAliasCoverageTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/route-alias-coverage");

    @Test
    @DisplayName("every recorded client constant is answered, and every alias is asked for")
    void thecommittedAliasesAndConstantsCorrespond() {
        assertEquals("", coverage().against(canonicalPaths()).render());
        assertTrue(coverage().constants().size() >= coverage().aliases().size(),
                "there are more aliases than there are client constants to justify them");
    }

    @Test
    @DisplayName("what a customer receives serves no alias at all")
    void whatAcustomerReceivesServesNoAlias() {
        assertEquals(List.of(), coverage().served(),
                "the shipped configuration turns an alias on, and /libs is a namespace a"
                        + " dispatcher passes more freely than anything else");
    }

    @Test
    @DisplayName("every alias states the client version and the correction it is waiting on")
    void everyaliasStatesItsOwnEnd() {
        coverage().aliases().forEach(alias -> {
            assertFalse(alias.clientVersion().isBlank(),
                    alias.path() + " states no client version");
            assertFalse(alias.pendingCorrection().isBlank(),
                    alias.path() + " states no correction, so nobody has agreed to remove it");
            assertTrue(alias.pendingCorrection().contains("crates/"),
                    alias.path() + " does not name the client file that has to change: "
                            + alias.pendingCorrection());
        });
    }

    @Test
    @DisplayName("a client constant nothing answers and an alias nobody asks for both fail")
    void bothDirectionsOfTheCorrespondenceAreRefused() {
        final PolicyReport unserved = at("constant-nothing-answers").against(canonicalPaths());
        assertTrue(unserved.render().contains("unserved-client-constant"), unserved.render());
        final PolicyReport unasked = at("alias-nobody-asks-for").against(canonicalPaths());
        assertTrue(unasked.render().contains("alias-nobody-asks-for"), unasked.render());
    }

    @Test
    @DisplayName("an alias with no end, and a shipped configuration that turns one on, both fail")
    void analiasWithNoEndAndAshippedOneBothFail() {
        final PolicyReport noEnd = at("alias-with-no-end").against(canonicalPaths());
        assertTrue(noEnd.render().contains("alias-with-no-end"), noEnd.render());
        final RouteAliasCoverage.Outcome shipped = RouteAliasCoverage.readBoth(
                REPOSITORY.resolve(RouteAliasCoverage.CONSTANTS_FILE),
                REPOSITORY.resolve(RouteAliasCoverage.ROUTES_FILE),
                FIXTURES.resolve("shipped-with-an-alias-on.json"));
        assertTrue(assertInstanceOf(RouteAliasCoverage.Loaded.class, shipped).coverage()
                        .against(canonicalPaths()).render().contains("alias-shipped-on"),
                "a shipped configuration serving an alias was accepted");
    }

    @Test
    @DisplayName("a constant declared for a half of the client nobody named is refused")
    void aconstantForAnUnknownHalfIsRefused() {
        assertInstanceOf(RouteAliasCoverage.Refused.class,
                RouteAliasCoverage.readBoth(FIXTURES.resolve("unknown-kind.toml"),
                        REPOSITORY.resolve(RouteAliasCoverage.ROUTES_FILE),
                        REPOSITORY.resolve(RouteAliasCoverage.SHIPPED_CONFIGURATION)),
                "a constant declared for a half of the client nobody named was accepted");
    }

    private static RouteAliasCoverage at(String fixture) {
        return assertInstanceOf(RouteAliasCoverage.Loaded.class,
                RouteAliasCoverage.readBoth(FIXTURES.resolve(fixture + "/constants.toml"),
                        FIXTURES.resolve(fixture + "/agent-routes.toml"),
                        REPOSITORY.resolve(RouteAliasCoverage.SHIPPED_CONFIGURATION)),
                fixture + " is not a pair this checker reads").coverage();
    }

    private static RouteAliasCoverage coverage() {
        return assertInstanceOf(RouteAliasCoverage.Loaded.class,
                RouteAliasCoverage.read(REPOSITORY), "the committed pair was refused").coverage();
    }

    private static List<String> canonicalPaths() {
        return List.of("/bin/slingshot/agent/capabilities", "/bin/slingshot/agent/submit",
                "/bin/slingshot/agent/snapshot", "/bin/slingshot/agent/jobs",
                "/bin/slingshot/agent/subscriptions/high-water", "/bin/slingshot/agent/events",
                "/bin/slingshot/agent/artifact");
    }
}
