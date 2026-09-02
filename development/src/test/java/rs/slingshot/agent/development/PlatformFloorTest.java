// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The oldest platform this build runs on, derived rather than declared.
 *
 * <p>Bound to the deployment matrix so that raising the floor is a change to the matrix, which is
 * already what the bytecode contract and the imported-package footprint check against. A floor
 * written down twice is two numbers that disagree quietly for as long as nobody compares them.</p>
 */
final class PlatformFloorTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    /** The release the bundles are compiled for, which every declared row provides. */
    private static final long BYTECODE_TARGET = 21;

    @Test
    @DisplayName("every supported row provides the runtime the bundles are compiled for")
    void everyrowProvidesWhatTheBundlesNeed() {
        assertEquals("", PlatformFloor.read(REPOSITORY).against(BYTECODE_TARGET).render());
    }

    @Test
    @DisplayName("a build compiled above every row's runtime is refused, naming both")
    void abuildAboveEveryRowIsRefused() {
        assertTrue(PlatformFloor.read(REPOSITORY).against(BYTECODE_TARGET + 1).findings().stream()
                        .anyMatch(finding -> PlatformFloor.ABOVE_EVERY_ROWS_RUNTIME
                                .equals(finding.rule())),
                "a build compiled for a runtime no row provides was accepted, and on that row"
                        + " nothing this product ships resolves at all");
    }

    @Test
    @DisplayName("the floor is the matrix's own oldest row rather than a number written down")
    void thefloorIsTheOldestRow() {
        final PlatformFloor floor = PlatformFloor.read(REPOSITORY);
        assertTrue(floor.rows().size() > 1,
                "there is one deployment row, so a floor over the rows proves nothing: "
                        + floor.rows());
        assertEquals(BYTECODE_TARGET, floor.oldestJavaRuntime(),
                "the oldest row's runtime is not the one the bundles are compiled for, so either"
                        + " the floor moved or the target did");
        assertTrue(!floor.oldestSlingVersion().isBlank()
                        && !floor.oldestOakVersion().isBlank(),
                "the floor over Sling or Oak is nothing, which is a floor nobody can be refused"
                        + " against");
    }

    @Test
    @DisplayName("a release is compared as numbers, because as text twelve is older than six")
    void areleaseIsComparedAsNumbers() {
        assertTrue(PlatformFloor.compareVersions("6.5", "12") < 0,
                "6.5 was not older than 12, which as a text comparison it would not be, and the"
                        + " floor would then be the wrong row");
        assertTrue(PlatformFloor.compareVersions("1.78", "1.8") > 0,
                "1.78 was older than 1.8, which is the other half of the same mistake");
        assertEquals(0, PlatformFloor.compareVersions("12", "12.0"),
                "a release and the same release with a nought are two different releases");
    }
}
