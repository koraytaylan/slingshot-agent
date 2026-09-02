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
 * Everything of this console a request can reach, and what has to be true of all of it.
 *
 * <p>The surface is derived from the package rather than listed, which is the whole point: a page
 * somebody adds next year is covered the day it is added rather than on the day somebody remembers
 * this file — and for the resource that gets forgotten, that day is never.</p>
 */
final class ConsoleSurfaceTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    @Test
    @DisplayName("every console resource is carried, covered, read-only and described")
    void thisConsoleSurfaceIsWhollyAccountedFor() {
        assertEquals("", ConsoleSurface.across(REPOSITORY).render());
    }

    @Test
    @DisplayName("the surface is derived from the package rather than written down")
    void thesurfaceIsDerivedRatherThanListed() {
        final ConsoleSurface.Surface surface = ConsoleSurface.of(REPOSITORY);
        assertTrue(surface.pages().size() > 1,
                "the console has one page or none, and this check would then be proving nothing: "
                        + surface.pages());
        assertEquals(surface.dataSources().size(), surface.screens().size(),
                "a data source is declared that nothing answers, or a screen exists that nothing"
                        + " reaches: " + surface.dataSources() + " against " + surface.screens());
        assertTrue(surface.pages().stream()
                        .allMatch(page -> page.startsWith("/apps/slingshot-agent/content/console")),
                "a console page sits somewhere the console's own authority does not guard: "
                        + surface.pages());
    }

    @Test
    @DisplayName("nothing on this console would change anything, which is what makes it one console")
    void nothingHereWrites() {
        assertTrue(ConsoleSurface.across(REPOSITORY).findings().stream()
                        .noneMatch(finding -> ConsoleSurface.STATE_CHANGE.equals(finding.rule())),
                "a console resource would change something, which would make this a second way to"
                        + " do what the routes do with a second authorization story");
    }

    @Test
    @DisplayName("the document and the package name the same pages, in both directions")
    void thedocumentAndThePackageAgree() {
        final List<String> documented =
                ConsoleSurface.documentedPages(RepositoryTree.text(
                        REPOSITORY.resolve(ConsoleSurface.DOCUMENT)));
        assertEquals(ConsoleSurface.of(REPOSITORY).pages().stream().sorted().toList(),
                documented.stream().sorted().toList(),
                "the document and the package disagree about which pages exist, and the one they"
                        + " disagree about is the one nobody has looked at");
    }

    @Test
    @DisplayName("the five rules are five, so a finding says which thing to fix")
    void thefiveRulesAreDistinct() {
        assertEquals(5, List.of(ConsoleSurface.UNCOVERED, ConsoleSurface.OUTSIDE_FILTER,
                        ConsoleSurface.STATE_CHANGE, ConsoleSurface.UNDOCUMENTED,
                        ConsoleSurface.UNKNOWN_PAGE).stream().distinct().count(),
                "two of the rules are spelled the same way");
    }
}
