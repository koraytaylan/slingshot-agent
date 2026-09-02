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
 * Whether the controls the deployments declare and the controls the commands need are one set.
 *
 * <p>Each rejection is proved on a copy of a committed file with exactly one thing wrong with it,
 * so a failure names the thing rather than the file. The committed pair is checked whole in the
 * first assertion, which is what makes the others mean something.</p>
 */
final class ControlCapabilityCoverageTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES = REPOSITORY.resolve(
            "development/src/test/resources/fixtures/control-capability");

    @Test
    @DisplayName("every deployment says something about every control, and every control is needed")
    void thecommittedMatrixAndMappingAgree() {
        assertEquals("", ControlCapabilityCoverage.against(
                ControlCapabilityCoverage.Sources.of(REPOSITORY)).render());
        assertTrue(ControlCapabilityCoverage.declaredCapabilities(
                        REPOSITORY.resolve(ControlCapabilityCoverage.CAPABILITY_SOURCE)).size()
                        >= 6,
                "the closed set of controls lost members, and this check reads it from the source"
                        + " rather than restating it precisely so that cannot happen quietly");
    }

    @Test
    @DisplayName("the controls are read from the enumeration rather than restated here")
    void thecontrolsComeFromTheSource() {
        final List<String> declared = ControlCapabilityCoverage.declaredCapabilities(
                REPOSITORY.resolve(ControlCapabilityCoverage.CAPABILITY_SOURCE));
        assertTrue(declared.contains("configuration_change")
                        && declared.contains("bundle_lifecycle"),
                "the two controls a Cloud Service environment does not provide are no longer in the"
                        + " set, and those are the whole reason this boundary exists: " + declared);
        assertEquals(List.of(), ControlCapabilityCoverage.declaredCapabilities(
                        FIXTURES.resolve("nothing.java")),
                "a source that is not there was read as declaring controls");
    }

    @Test
    @DisplayName("a row that refuses a control without saying why is rejected")
    void anunexplainedAbsenceIsRejected() {
        assertRule(againstMatrix("unexplained-absence.toml"),
                ControlCapabilityCoverage.UNEXPLAINED, "says nothing about why");
    }

    @Test
    @DisplayName("a row that says nothing at all about a control is rejected, distinctly")
    void anundeclaredControlIsRejected() {
        assertRule(againstMatrix("silent-row.toml"),
                ControlCapabilityCoverage.UNDECLARED, "says nothing about");
    }

    @Test
    @DisplayName("a command needing a control nobody declared, and one nobody needs, are distinct")
    void thetwoMappingRejectionsAreDistinct() {
        assertRule(againstMapping("unknown-control.toml"),
                ControlCapabilityCoverage.UNKNOWN, "not one of the controls there are");
        assertRule(againstMapping("unknown-control.toml"),
                ControlCapabilityCoverage.UNUSED, "no command in this product needs");
    }

    @Test
    @DisplayName("a mapping naming a command neither half publishes is rejected")
    void anunpublishedCommandIsRejected() {
        assertRule(againstMapping("unpublished-command.toml"),
                ControlCapabilityCoverage.UNPUBLISHED, "is not a command the client publishes");
    }

    private static String againstMatrix(String fixture) {
        return ControlCapabilityCoverage.against(ControlCapabilityCoverage.Sources.of(REPOSITORY)
                .withMatrix(FIXTURES.resolve(fixture))).render();
    }

    private static String againstMapping(String fixture) {
        return ControlCapabilityCoverage.against(ControlCapabilityCoverage.Sources.of(REPOSITORY)
                .withMapping(FIXTURES.resolve(fixture))).render();
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
