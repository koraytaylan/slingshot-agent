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
 * Whether anything this product writes would shadow the platform or remove somebody else's content.
 *
 * <p>Each rejection is proved on a fixture with exactly one thing wrong with it. The committed
 * packages are checked whole in the first assertion, which is what makes the others mean
 * something.</p>
 */
final class OverlayAuditTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/overlay-audit");

    @Test
    @DisplayName("nothing this product writes sits on top of a resource the platform provides")
    void nothingShadowsThePlatform() {
        assertEquals("", OverlayAudit.against(OverlayAudit.Sources.of(REPOSITORY)).render());
        assertTrue(OverlayAudit.writtenPaths(REPOSITORY.resolve(OverlayAudit.CONTENT_ROOT))
                        .contains(OverlayAudit.PERMITTED_LEAF),
                "the one leaf this product adds under Adobe's own tools navigation is gone, so an"
                        + " operator who has just installed this finds nothing under Tools and"
                        + " concludes nothing was installed — and they are not wrong to");
    }

    @Test
    @DisplayName("a path at an extension point shadows it; a path under one is what it is for")
    void aleafIsNotAnOverlay() {
        assertTrue(OverlayAudit.shadows("/apps/cq/core/content/nav/tools"),
                "writing the navigation itself was not reported as an overlay, and doing that"
                        + " replaces the navigation rather than appearing in it");
        assertTrue(!OverlayAudit.shadows(OverlayAudit.PERMITTED_LEAF),
                "this product's own leaf under the extension point was reported as an overlay,"
                        + " which is exactly what an extension point is for");
        assertTrue(OverlayAudit.shadows("/libs/granite/ui/components/shell/page"),
                "a path under Adobe's own tree was not reported as an overlay");
        assertTrue(!OverlayAudit.shadows("/apps/slingshot-agent/content/console"),
                "this product's own tree was reported as an overlay");
    }

    @Test
    @DisplayName("a package writing over an Adobe resource fails, naming the path")
    void ashadowingPackageIsRefused() {
        assertRule(OverlayAudit.against(OverlayAudit.Sources.of(REPOSITORY)
                        .withContent(FIXTURES.resolve("shadowing/jcr_root"))).render(),
                OverlayAudit.SHADOWS_PLATFORM, "/apps/cq/core/content/nav/tools");
        assertEquals("", OverlayAudit.against(OverlayAudit.Sources.of(REPOSITORY)
                        .withContent(FIXTURES.resolve("accepted/jcr_root"))).render(),
                "a package writing only inside this product's own root was refused");
    }

    @Test
    @DisplayName("a filter reaching outside the declared roots is refused, and so is one at a parent")
    void thetwoFilterRejectionsAreDistinct() {
        assertRule(againstFilter("wide-filter.xml"), OverlayAudit.FILTER_TOO_WIDE,
                "outside every root the structure package declares");
        assertRule(againstFilter("parent-filter.xml"), OverlayAudit.FILTER_TOO_WIDE,
                "/apps/cq/core/content/nav/tools");
    }

    @Test
    @DisplayName("the committed filter covers the declared roots and the one navigation leaf")
    void thecommittedFilterIsExact() {
        final List<String> roots = OverlayAudit.rootsIn(REPOSITORY.resolve(
                OverlayAudit.FILTER_FILE));
        assertTrue(roots.contains(OverlayAudit.PERMITTED_LEAF),
                "the filter no longer covers the navigation leaf, so installing writes a node"
                        + " that uninstalling would leave behind: " + roots);
        assertTrue(!roots.contains("/apps/cq/core/content/nav/tools"),
                "the filter reaches the navigation parent, so uninstalling would take every other"
                        + " product's entry with it — on somebody else's instance rather than"
                        + " here");
        final List<String> declared = OverlayAudit.rootsIn(REPOSITORY.resolve(
                OverlayAudit.STRUCTURE_FILTER));
        roots.forEach(rule -> assertTrue(declared.stream().anyMatch(root -> rule.equals(root)
                        || rule.startsWith(root + "/")),
                rule + " is outside every root the structure package declares"));
    }

    private static String againstFilter(String fixture) {
        return OverlayAudit.against(OverlayAudit.Sources.of(REPOSITORY)
                .withFilter(FIXTURES.resolve(fixture))).render();
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
