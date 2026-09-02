// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether this repository has acquired a front-end toolchain.
 *
 * <p>The committed state is checked whole in the first assertion; each rejection is proved on a
 * fixture with exactly one thing wrong with it.</p>
 */
final class FrontEndFootprintTest {

    private static final Path REPOSITORY = RepositoryTree.locate();

    private static final Path FIXTURES =
            REPOSITORY.resolve("development/src/test/resources/fixtures/front-end-footprint");

    @Test
    @DisplayName("the front end is one script, one stylesheet, and no toolchain")
    void thefrontEndIsStillOneOfEach() {
        assertEquals("", FrontEndFootprint.against(FrontEndFootprint.Sources.of(REPOSITORY))
                .render());
        assertEquals(FrontEndFootprint.ONE_SCRIPT,
                FrontEndFootprint.assetsUnder(
                        REPOSITORY.resolve(FrontEndFootprint.CONTENT_ROOT), ".js").size(),
                "this product ships a number of scripts other than the one it declares");
        assertEquals(FrontEndFootprint.ONE_STYLESHEET,
                FrontEndFootprint.assetsUnder(
                        REPOSITORY.resolve(FrontEndFootprint.CONTENT_ROOT), ".css").size());
    }

    @Test
    @DisplayName("a package manager's manifest anywhere in the repository is refused")
    void amanifestIsRefused() {
        assertRule(FrontEndFootprint.against(FrontEndFootprint.Sources.of(REPOSITORY)
                        .withRoot(FIXTURES.resolve("toolchain"))).render(),
                FrontEndFootprint.MANIFEST_PRESENT, "second dependency graph");
        assertEquals(java.util.List.of(), FrontEndFootprint.manifestsUnder(REPOSITORY),
                "a package manager's manifest is committed somewhere in this repository");
    }

    @Test
    @DisplayName("a second script is refused, naming how many there are")
    void asecondScriptIsRefused() {
        assertRule(FrontEndFootprint.against(FrontEndFootprint.Sources.of(REPOSITORY)
                        .withContent(FIXTURES.resolve("many/jcr_root"))).render(),
                FrontEndFootprint.TOO_MANY_ASSETS, "2 scripts");
    }

    @Test
    @DisplayName("a script pulling something from elsewhere is refused")
    void aremoteScriptIsRefused() {
        assertRule(FrontEndFootprint.against(FrontEndFootprint.Sources.of(REPOSITORY)
                        .withContent(FIXTURES.resolve("remote/jcr_root"))).render(),
                FrontEndFootprint.REMOTE_SCRIPT, "nobody's build can reproduce");
    }

    @Test
    @DisplayName("every manifest a package manager reads is named rather than detected")
    void themanifestsAreNamed() {
        assertTrue(FrontEndFootprint.MANIFESTS.contains("package.json")
                        && FrontEndFootprint.MANIFESTS.contains("webpack.config.js")
                        && FrontEndFootprint.MANIFESTS.contains("tsconfig.json"),
                "a manifest somebody would actually add is no longer named: "
                        + FrontEndFootprint.MANIFESTS);
    }

    private static void assertRule(String rendered, String rule, String detail) {
        assertTrue(rendered.contains(rule) && rendered.contains(detail),
                "the finding does not name " + rule + " and " + detail + ": " + rendered);
    }
}
