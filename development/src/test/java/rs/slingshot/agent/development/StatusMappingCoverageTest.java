// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mapping and the categories, compared in both directions on this repository itself.
 */
final class StatusMappingCoverageTest {

    private static final Path REPOSITORY = repositoryRoot();

    @Test
    @DisplayName("every declared category is mapped and every mapped category is declared")
    void thetwoSetsAreOneSet() {
        assertEquals("", StatusMappingCoverage.against(REPOSITORY).render());
        assertEquals(StatusMappingCoverage.declaredCategories(REPOSITORY).stream().sorted()
                        .toList(),
                StatusMappingCoverage.mappedCategories(REPOSITORY).stream().sorted().toList(),
                "the mapping and the categories are not the same set");
    }

    @Test
    @DisplayName("the categories are read from the source that declares them")
    void thecategoriesAreReadFromTheSource() {
        assertTrue(StatusMappingCoverage.declaredCategories(REPOSITORY).contains("unauthenticated"),
                "the categories were not read from where they are declared");
        assertFalse(StatusMappingCoverage.declaredCategories(REPOSITORY).isEmpty());
        assertTrue(StatusMappingCoverage.mappedCategories(REPOSITORY).contains("capacity_exhausted"),
                "the mapping was not read from where it is committed");
    }

    private static Path repositoryRoot() {
        final String declared = System.getProperty("slingshot.repository.root");
        assertTrue(declared != null && !declared.isBlank(),
                "the repository root is not declared; run this through the build");
        return Path.of(declared).toAbsolutePath().normalize();
    }
}
