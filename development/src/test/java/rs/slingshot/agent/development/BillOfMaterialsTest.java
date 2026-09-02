// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a release contains, and what it binds to without containing.
 *
 * <p>The second half is the one worth stating loudly. This product embeds nothing, so a components
 * list that named only what is inside the archives would say the release depends on nothing — true
 * about the archive and false about the software.</p>
 */
final class BillOfMaterialsTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    @Test
    @DisplayName("a bill generated from this repository agrees with it in both directions")
    void agenreratedBillAgreesWithTheRelease() {
        assertEquals("", BillOfMaterials.against(REPOSITORY,
                        BillOfMaterials.contained(REPOSITORY),
                        BillOfMaterials.boundAtRuntime(REPOSITORY)).render());
    }

    @Test
    @DisplayName("a component the archives do not contain is refused, naming it")
    void acomponentTheArchivesDoNotHoldIsRefused() {
        final List<String> listed = new ArrayList<>(BillOfMaterials.contained(REPOSITORY));
        listed.add("nothing-like-this");
        assertTrue(BillOfMaterials.against(REPOSITORY, listed,
                        BillOfMaterials.boundAtRuntime(REPOSITORY)).findings().stream()
                        .anyMatch(finding -> BillOfMaterials.A_COMPONENT_NOT_CONTAINED
                                .equals(finding.rule())),
                "a component nothing contains was accepted, which is a release claiming to hold"
                        + " something it does not");
    }

    @Test
    @DisplayName("an artifact with no component row is refused, which is the other direction")
    void anartifactWithNoComponentIsRefused() {
        final List<String> listed = new ArrayList<>(BillOfMaterials.contained(REPOSITORY));
        listed.removeFirst();
        assertTrue(BillOfMaterials.against(REPOSITORY, listed,
                        BillOfMaterials.boundAtRuntime(REPOSITORY)).findings().stream()
                        .anyMatch(finding -> BillOfMaterials.AN_ARTIFACT_WITH_NO_COMPONENT
                                .equals(finding.rule())),
                "an artifact the release contains and the list omits was accepted, and the omitted"
                        + " one is always the one somebody added in a hurry");
    }

    @Test
    @DisplayName("what this binds to at run time is stated, even though it embeds none of it")
    void whatItBindsToIsStated() {
        final List<String> bound = BillOfMaterials.boundAtRuntime(REPOSITORY);
        assertTrue(bound.size() > 5,
                "this release is described as binding to almost nothing, which is true about the"
                        + " archive and false about the software: " + bound);
        assertTrue(bound.stream().anyMatch(coordinate -> coordinate.contains("sling")),
                "nothing it binds to is a Sling package, and every route it serves is one");
        assertTrue(BillOfMaterials.against(REPOSITORY, BillOfMaterials.contained(REPOSITORY),
                        List.of("nothing:like-this")).findings().stream()
                        .anyMatch(finding -> BillOfMaterials.A_RELATIONSHIP_NOBODY_DECLARED
                                .equals(finding.rule())),
                "a relationship no dependency declares was accepted");
    }

    @Test
    @DisplayName("the list itself is beside the release rather than inside it")
    void thelistIsNotAComponentOfItself() {
        assertTrue(BillOfMaterials.contained(REPOSITORY).stream()
                        .noneMatch(component -> component.contains("bill-of-materials")),
                "the components list lists itself, which is one entry nobody can check");
    }

    @Test
    @DisplayName("a bill is generated rather than maintained, so two reads agree")
    void abillIsGeneratedRatherThanMaintained() {
        assertEquals(BillOfMaterials.generated(REPOSITORY), BillOfMaterials.generated(REPOSITORY));
        assertTrue(BillOfMaterials.generated(REPOSITORY).contains("bindsAtRuntime"),
                "the generated bill says nothing about what this binds to at run time");
    }
}
