// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Everything the registry enforces, decided here rather than discovered from a rejection.
 *
 * <p>A release refused remotely is one somebody works out the reason for from whatever the portal
 * said. A release refused here names all of it at once, offline, before anything was built.</p>
 *
 * <p>The prerequisites are data so somebody can compare them with the registry's own published
 * requirements. A script that happened to check nine things is a script nobody can audit against
 * anything.</p>
 */
final class CentralPrerequisitesTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /**
     * A tree that is not ready to publish: a snapshot version, and an owner who supplied nothing.
     *
     * <p>Every refusal here was read from this repository while that was true of it. An owner has
     * since supplied what only an owner can, and the version is a release, so the refusals are
     * asked of a tree where they still apply and this repository is asked what it now says.</p>
     */
    private static final Path NOTHING_READY = REPOSITORY.resolve(
            "development/src/test/resources/fixtures/central-prerequisites/nothing-ready");

    /** How many prerequisites this repository declares. */
    private static final int DECLARED = 10;

    @Test
    @DisplayName("every prerequisite but the two an owner supplies is already satisfied")
    void themodelSatisfiesWhatItCan() {
        final List<PolicyFinding> findings = prerequisites().against(REPOSITORY).findings();
        assertTrue(findings.stream()
                        .allMatch(finding -> finding.symbol().contains("signature")
                                || finding.symbol().contains("SNAPSHOT")),
                "something other than the signing identity and the snapshot version is unsatisfied,"
                        + " and everything else is this repository's own to declare: " + findings);
    }

    @Test
    @DisplayName("a snapshot version is refused separately, because it is not a missing field")
    void asnapshotIsItsOwnRefusal() {
        assertTrue(prerequisites().against(NOTHING_READY).findings().stream()
                        .anyMatch(finding -> CentralPrerequisites.A_SNAPSHOT_VERSION
                                .equals(finding.rule())),
                "a snapshot version was accepted, and a version that means something different"
                        + " tomorrow is a promise rather than an artifact");
    }

    @Test
    @DisplayName("every failure is reported at once rather than the first")
    void everyfailureIsReportedAtOnce() {
        assertTrue(prerequisites().against(NOTHING_READY).findings().size() > 1,
                "one failure was reported, and somebody about to release wants the list rather"
                        + " than one line of it per attempt");
    }

    @Test
    @DisplayName("the prerequisites are data somebody can compare with the registry's own list")
    void theprerequisitesAreData() {
        assertEquals(DECLARED, prerequisites().prerequisites().size(),
                "the list is no longer the one this repository declares, so comparing it with the"
                        + " registry's own published requirements would compare the wrong thing");
        assertEquals(DECLARED, prerequisites().prerequisites().stream()
                        .map(CentralPrerequisites.Prerequisite::identifier).distinct().count(),
                "two prerequisites are named the same way");
    }

    @Test
    @DisplayName("an element nothing satisfies is refused rather than passed over")
    void anunknownElementIsRefused() {
        assertTrue(!prerequisitesAt("unknown-element.toml").against(REPOSITORY).findings()
                        .isEmpty(),
                "a prerequisite naming something this check knows nothing about was treated as"
                        + " satisfied, which is a requirement nobody is held to");
    }

    @Test
    @DisplayName("each prerequisite is decided on its own, so a fixture holding one holds one")
    void eachprerequisiteIsDecidedOnItsOwn() {
        assertEquals(List.of(), prerequisitesAt("project-name.toml").against(REPOSITORY).findings()
                        .stream()
                        .filter(finding -> CentralPrerequisites.NOT_SATISFIED
                                .equals(finding.rule()))
                        .toList(),
                "the model declares a name and the check said it does not");
        assertTrue(!prerequisitesAt("signature.toml").against(NOTHING_READY).findings().isEmpty(),
                "nobody is declared as signing and the check said somebody is");
    }

    private static CentralPrerequisites prerequisitesAt(String fixture) {
        return assertInstanceOf(CentralPrerequisites.Loaded.class,
                CentralPrerequisites.readFile(REPOSITORY.resolve(
                        "development/src/test/resources/fixtures/central-prerequisites")
                        .resolve(fixture)),
                fixture + " did not read").prerequisites();
    }

    private static CentralPrerequisites prerequisites() {
        return assertInstanceOf(CentralPrerequisites.Loaded.class,
                CentralPrerequisites.read(REPOSITORY),
                "the prerequisites did not read").prerequisites();
    }
}
