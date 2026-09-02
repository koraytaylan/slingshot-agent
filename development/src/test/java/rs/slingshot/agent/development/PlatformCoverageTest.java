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
 * Whether every command that changes the platform is gated and claimed.
 *
 * <p>Each rejection is proved on a copy of a committed file with exactly one thing wrong with it,
 * so a failure names the thing. The committed set is checked whole in the first assertion, which is
 * what makes the others mean something.</p>
 */
final class PlatformCoverageTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/platform-coverage");

    @Test
    @DisplayName("every platform control is gated by a capability and claimed by one suite")
    void everycontrolIsGatedAndClaimed() {
        assertEquals("", PlatformCoverage.against(PlatformCoverage.Sources.of(REPOSITORY))
                .render());
        final List<String> controls = PlatformCoverage.controlsIn(
                REPOSITORY.resolve(PlatformCoverage.REGISTRY_DIRECTORY));
        assertTrue(controls.size() >= 10,
                "the registry declares fewer platform controls than this plan built, so this check"
                        + " is passing over an empty set rather than proving one: " + controls);
        assertTrue(controls.contains("cancel_sling_job")
                        && controls.contains("update_open_service_gateway_initiative_configuration"),
                "a command this plan built as a platform control no longer declares itself one");
    }

    @Test
    @DisplayName("the controls are read from the registry rather than restated here")
    void thecontrolsComeFromTheRegistry() {
        assertEquals(List.of(), PlatformCoverage.controlsIn(FIXTURES.resolve("nowhere")),
                "a registry that is not there was read as declaring platform controls");
        assertTrue(PlatformCoverage.changingIn(
                        REPOSITORY.resolve(PlatformCoverage.REGISTRY_DIRECTORY)).size()
                        > PlatformCoverage.controlsIn(
                                REPOSITORY.resolve(PlatformCoverage.REGISTRY_DIRECTORY)).size(),
                "every command that changes something is a platform control, which would mean the"
                        + " commands that write to the caller's own repository have been"
                        + " misclassified");
    }

    @Test
    @DisplayName("a control no deployment can refuse is a finding, naming the command")
    void anungatedControlIsRefused() {
        assertRule(againstRegistry("ungated"), PlatformCoverage.UNGATED,
                "no deployment can refuse it");
    }

    @Test
    @DisplayName("a capability in front of a command that changes nothing is a finding")
    void anovergatedCommandIsRefused() {
        assertRule(PlatformCoverage.against(PlatformCoverage.Sources.of(REPOSITORY)
                        .withMapping(FIXTURES.resolve("overgated.toml"))).render(),
                PlatformCoverage.OVERGATED, "gated for something it does not do");
    }

    @Test
    @DisplayName("a command that changes the platform and commits as well is two commands")
    void arowDeclaringBothOutcomesIsRefused() {
        assertRule(againstRegistry("both-outcomes"), PlatformCoverage.BOTH_OUTCOMES,
                "a command that does both is two commands");
    }

    @Test
    @DisplayName("the platform category is claimed by a scenario, and an unclaimed one is a finding")
    void anunclaimedCategoryIsRefused() {
        assertTrue(MutationCoverage.claims(
                        REPOSITORY.resolve(MutationCoverage.SCENARIO_DIRECTORY))
                        .containsKey(PlatformCoverage.PLATFORM_OUTCOME),
                "no committed scenario claims the platform control's own category, so nothing"
                        + " proves what the thirty have in common");
        assertRule(PlatformCoverage.against(PlatformCoverage.Sources.of(REPOSITORY)
                        .withScenarios(FIXTURES.resolve("nowhere"))).render(),
                PlatformCoverage.UNCLAIMED, "no cross-cutting scenario claims");
    }

    private static String againstRegistry(String fixture) {
        return PlatformCoverage.against(PlatformCoverage.Sources.of(REPOSITORY)
                .withRegistry(FIXTURES.resolve(fixture).resolve("commands"))).render();
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
