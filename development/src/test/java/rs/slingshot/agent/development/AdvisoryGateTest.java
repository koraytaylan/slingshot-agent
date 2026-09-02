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
 * Whether the advisory snapshot is the one an owner reviewed, decided without reaching anything.
 *
 * <p>There is no timestamp and no freshness claim, because a snapshot's author chooses both and
 * neither authenticates anything. What is checkable is whether these are the bytes somebody
 * reviewed, and that is what is checked.</p>
 *
 * <p>The three refusals are kept apart because they are three different things to do next, and
 * none of them checks a single artifact: checking against a snapshot nobody authenticated is
 * checking against whatever happens to be on the machine.</p>
 */
final class AdvisoryGateTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/advisory");

    @Test
    @DisplayName("a snapshot nobody fetched, one that is absent, and one that differs are three")
    void thethreeRefusalsAreThree() {
        assertRule("not-fetched.toml", AdvisoryGate.NOT_FETCHED);
        assertRule("not-present.toml", AdvisoryGate.NOT_PRESENT);
        assertRule("not-the-pinned-snapshot.toml", AdvisoryGate.NOT_THE_PINNED_SNAPSHOT);
        assertEquals(3, List.of(AdvisoryGate.NOT_FETCHED, AdvisoryGate.NOT_PRESENT,
                        AdvisoryGate.NOT_THE_PINNED_SNAPSHOT).stream().distinct().count(),
                "two of the three refusals are spelled the same way, and they are three different"
                        + " things to do next");
    }

    @Test
    @DisplayName("none of the three checks anything, because there is nothing to check against")
    void nothingIsCheckedAgainstAnUnauthenticatedSnapshot() {
        List.of("not-fetched.toml", "not-present.toml", "not-the-pinned-snapshot.toml")
                .forEach(fixture -> assertEquals(1, gateAt(fixture)
                                .authentication(REPOSITORY).findings().size(),
                        fixture + " reported more than the one reason it stopped, which means it"
                                + " went on to check artifacts against a snapshot nobody"
                                + " authenticated"));
    }

    @Test
    @DisplayName("the pin cannot carry a date, which is the enforcement rather than a convention")
    void thepinCannotCarryADate() {
        assertInstanceOf(AdvisoryGate.Refused.class,
                AdvisoryGate.readFile(FIXTURES.resolve("carries-a-timestamp.toml")),
                "a pin carrying a date was accepted, and a snapshot's author chooses a date, so a"
                        + " gate that read one would be reporting something it cannot check");
    }

    @Test
    @DisplayName("build-time and test-scope artifacts are checked, because they are code that runs")
    void everyscopeIsChecked() {
        assertTrue(gate().checkedScopes().containsAll(List.of("compile", "provided", "test")),
                "a scope is left out, and a build-time dependency with a problem is a problem in"
                        + " the place that has the credentials: " + gate().checkedScopes());
    }

    @Test
    @DisplayName("this repository's own pin says plainly that nothing has been fetched yet")
    void thisRepositorysPinIsHonestAboutBeingEmpty() {
        assertTrue(gate().authentication(REPOSITORY).findings().stream()
                        .anyMatch(finding -> AdvisoryGate.NOT_FETCHED.equals(finding.rule())),
                "the pin claims a snapshot nobody has fetched, which is worse than claiming none");
    }

    @Test
    @DisplayName("a tree's digest is the same twice and different for different bytes")
    void adigestIsTheBytesAndNothingElse() {
        assertEquals(AdvisoryGate.digestOf(FIXTURES), AdvisoryGate.digestOf(FIXTURES),
                "the same tree digested to two different values, which would make every"
                        + " authentication a coin toss");
        assertTrue(!AdvisoryGate.digestOf(FIXTURES).equals(
                        AdvisoryGate.digestOf(FIXTURES.getParent())),
                "two different trees digested the same, so a substituted snapshot would pass");
    }

    private static void assertRule(String fixture, String rule) {
        assertTrue(gateAt(fixture).authentication(REPOSITORY).findings().stream()
                        .anyMatch(finding -> rule.equals(finding.rule())),
                fixture + " was not refused under " + rule + ": "
                        + gateAt(fixture).authentication(REPOSITORY).render());
    }

    private static AdvisoryGate gateAt(String fixture) {
        return assertInstanceOf(AdvisoryGate.Loaded.class,
                AdvisoryGate.readFile(FIXTURES.resolve(fixture)),
                fixture + " did not read").gate();
    }

    private static AdvisoryGate gate() {
        return assertInstanceOf(AdvisoryGate.Loaded.class, AdvisoryGate.read(REPOSITORY),
                "the advisory pin did not read").gate();
    }
}
