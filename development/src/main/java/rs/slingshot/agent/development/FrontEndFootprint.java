// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Whether this repository has acquired a front-end toolchain.
 *
 * <p>A front-end pipeline is a second dependency graph: its own licences, its own advisories, its
 * own upgrade cadence, and its own lock file that nobody in a Java review reads. Adding several
 * hundred packages to render four tables would undo the compatibility argument the whole repository
 * rests on — that everything here is one reactor, built offline, from a dependency set somebody can
 * enumerate.</p>
 *
 * <p>The bound is deliberately crude: a count of scripts and stylesheets, and a refusal of any
 * manifest a package manager reads. A more nuanced rule would be a rule somebody argues with; a
 * count is a number that either went up or did not, and the day it goes up is the day somebody has
 * to say why in a commit message.</p>
 */
public final class FrontEndFootprint {

    private FrontEndFootprint() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "front-end-footprint";

    /** Where the content package's own content sits. */
    public static final String CONTENT_ROOT = "ui.apps/src/main/content/jcr_root";

    /** How many scripts this product ships, and there is no second. */
    public static final int ONE_SCRIPT = 1;

    /** How many stylesheets it ships. */
    public static final int ONE_STYLESHEET = 1;

    /**
     * The files a package manager reads, none of which may exist anywhere in this repository.
     *
     * <p>Named rather than detected, because each of these is somebody deliberately starting a
     * second dependency graph and each has a distinct name they typed.</p>
     */
    public static final List<String> MANIFESTS = List.of("package.json", "package-lock.json",
            "yarn.lock", "pnpm-lock.yaml", "bower.json", "webpack.config.js", "vite.config.js",
            "rollup.config.js", "tsconfig.json", ".babelrc", "gulpfile.js");

    /** A package manager's manifest, anywhere in the repository. */
    public static final String MANIFEST_PRESENT = "front-end-manifest-present";

    /** More scripts or stylesheets than this product ships. */
    public static final String TOO_MANY_ASSETS = "more-front-end-assets-than-declared";

    /** A script pulled from somewhere other than this repository. */
    public static final String REMOTE_SCRIPT = "script-loaded-from-elsewhere";

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param root the repository root, walked for manifests
     * @param content the directory the content package's own content sits in
     */
    public record Sources(Path root, Path content) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root, root.resolve(CONTENT_ROOT));
        }

        /**
         * The same sources with the package content read from somewhere else.
         *
         * @param elsewhere where the content sits instead
         * @return the sources
         */
        public Sources withContent(Path elsewhere) {
            return new Sources(root, elsewhere);
        }

        /**
         * The same sources with the repository walked from somewhere else.
         *
         * @param elsewhere where to walk instead
         * @return the sources
         */
        public Sources withRoot(Path elsewhere) {
            return new Sources(elsewhere, content);
        }
    }

    /**
     * Whether the front end is still one script, one stylesheet, and no toolchain.
     *
     * @param sources everywhere to read from
     * @return one finding per thing that says otherwise
     */
    public static PolicyReport against(Sources sources) {
        final List<PolicyFinding> findings = new ArrayList<>();
        manifestsUnder(sources.root()).forEach(manifest -> findings.add(PolicyFinding.inFile(
                manifest, MANIFEST_PRESENT, manifest + " is a package manager's manifest, and one"
                        + " of those is a second dependency graph with its own licences, its own"
                        + " advisories and its own upgrade cadence")));
        final List<String> scripts = assetsUnder(sources.content(), ".js");
        final List<String> styles = assetsUnder(sources.content(), ".css");
        if (scripts.size() > ONE_SCRIPT) {
            findings.add(PolicyFinding.inFile(CONTENT_ROOT, TOO_MANY_ASSETS, scripts.size()
                    + " scripts, and this product ships " + ONE_SCRIPT + ": " + scripts));
        }
        if (styles.size() > ONE_STYLESHEET) {
            findings.add(PolicyFinding.inFile(CONTENT_ROOT, TOO_MANY_ASSETS, styles.size()
                    + " stylesheets, and this product ships " + ONE_STYLESHEET + ": " + styles));
        }
        findings.addAll(remoteFindings(sources.content(), scripts));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> remoteFindings(Path content, List<String> scripts) {
        return scripts.stream()
                .filter(script -> read(content.resolve(script.substring(1))).contains("//"
                        + "cdn"))
                .map(script -> PolicyFinding.inFile(CONTENT_ROOT, REMOTE_SCRIPT, script
                        + " loads something from elsewhere, which is a dependency nobody in this"
                        + " repository can enumerate and nobody's build can reproduce"))
                .toList();
    }

    /**
     * Every package manager manifest anywhere in the repository.
     *
     * @param root the repository root
     * @return their repository-relative paths, in ascending order
     */
    public static List<String> manifestsUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> held = Files.walk(root)) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> MANIFESTS.contains(String.valueOf(file.getFileName())))
                    .filter(file -> !root.relativize(file).toString().contains("target"))
                    .filter(file -> !root.relativize(file).toString().contains("fixtures"))
                    .filter(file -> !root.relativize(file).toString()
                            .contains(".dependency-cache"))
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Every asset of one kind the content package ships.
     *
     * @param content the directory the content sits in
     * @param extension what the file names end with
     * @return their paths under the content root, in ascending order
     */
    public static List<String> assetsUnder(Path content, String extension) {
        if (!Files.isDirectory(content)) {
            return List.of();
        }
        try (Stream<Path> held = Files.walk(content)) {
            return held.filter(Files::isRegularFile)
                    .filter(file -> String.valueOf(file.getFileName()).endsWith(extension))
                    .map(file -> "/" + content.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
