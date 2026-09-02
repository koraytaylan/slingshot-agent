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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether anything this product writes would shadow a resource the platform provides.
 *
 * <p>Adobe's extension points are meant to be added to. A package that writes a node <em>over</em>
 * one the platform ships works perfectly on the day it is installed and stops working on the day
 * the platform changes the resource underneath — which is an upgrade, months later, done by
 * somebody who has never heard of this product. That is the worst possible time to find out, so it
 * is found here instead.</p>
 *
 * <p>Two separate things are checked and they fail differently. A path that shadows a platform
 * resource is an overlay; a filter rule reaching outside what the structure package declares is a
 * package that removes somebody else's content when it is uninstalled. The first is a defect that
 * appears at upgrade, the second at uninstall, and an operator experiencing either has no reason to
 * suspect this product.</p>
 */
public final class OverlayAudit {

    private OverlayAudit() {
    }

    /** The rule every finding here is reported under. */
    public static final String RULE = "overlay-audit";

    /** Where the content package's own filter is declared. */
    public static final String FILTER_FILE =
            "ui.apps/src/main/content/META-INF/vault/filter.xml";

    /**
     * Where the structure package declares the roots every other package writes inside.
     *
     * <p>Its own build file rather than a filter document: the structure package declares its roots
     * to the packaging plugin and has no content of its own, which is the whole point of it. Read
     * from where they are written rather than from where a filter would be if there were one.</p>
     */
    public static final String STRUCTURE_FILTER = "ui.apps.structure/pom.xml";

    /** Where the configuration package's own filter is declared. */
    public static final String CONFIGURATION_FILTER =
            "ui.config/src/main/content/META-INF/vault/filter.xml";

    /** Where the content package's own content sits. */
    public static final String CONTENT_ROOT = "ui.apps/src/main/content/jcr_root";

    /**
     * The trees the platform provides, which nothing here may write into.
     *
     * <p>{@code /libs} is Adobe's own and has never been anybody else's to write. The others are
     * the roots whose <em>leaves</em> a product may add to and whose own nodes it may not: writing
     * {@code /apps/cq/core/content/nav/tools} itself would replace the navigation rather than
     * appear in it.</p>
     */
    public static final List<String> PLATFORM_TREES =
            List.of("/libs", "/apps/cq", "/apps/dam", "/apps/wcm", "/apps/granite", "/apps/core");

    /** The one leaf under a platform tree this product is allowed to add. */
    public static final String PERMITTED_LEAF = "/apps/cq/core/content/nav/tools/slingshot-agent";

    /** A path that would sit on top of a resource the platform provides. */
    public static final String SHADOWS_PLATFORM = "path-shadows-a-platform-resource";

    /** A filter rule reaching outside what the structure package declares. */
    public static final String FILTER_TOO_WIDE = "filter-reaches-outside-the-declared-roots";

    /** A filter rule that would remove more than this package created. */
    public static final String FILTER_REMOVES_MORE = "filter-would-remove-more-than-it-wrote";

    /**
     * Everywhere this check reads from, so a fixture can replace one input and no other.
     *
     * @param content the directory the content package's own content sits in
     * @param filter the content package's filter
     * @param structure the structure package's filter, which declares the roots
     */
    public record Sources(Path content, Path filter, Path structure) {

        /**
         * Everywhere this check reads from in a repository laid out the way this one is.
         *
         * @param root the repository root
         * @return the sources
         */
        public static Sources of(Path root) {
            return new Sources(root.resolve(CONTENT_ROOT), root.resolve(FILTER_FILE),
                    root.resolve(STRUCTURE_FILTER));
        }

        /**
         * The same sources with the package content read from somewhere else.
         *
         * @param elsewhere where the content sits instead
         * @return the sources
         */
        public Sources withContent(Path elsewhere) {
            return new Sources(elsewhere, filter, structure);
        }

        /**
         * The same sources with the package filter read from somewhere else.
         *
         * @param elsewhere where the filter sits instead
         * @return the sources
         */
        public Sources withFilter(Path elsewhere) {
            return new Sources(content, elsewhere, structure);
        }
    }

    /**
     * Whether anything this product writes shadows the platform or reaches outside its roots.
     *
     * @param sources everywhere to read from
     * @return one finding per path or rule that does, each naming what it is about
     */
    public static PolicyReport against(Sources sources) {
        final List<PolicyFinding> findings = new ArrayList<>(shadowFindings(sources.content()));
        findings.addAll(filterFindings(sources));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> shadowFindings(Path content) {
        return writtenPaths(content).stream()
                .filter(OverlayAudit::shadows)
                .map(path -> PolicyFinding.inFile(CONTENT_ROOT, SHADOWS_PLATFORM, path
                        + " sits on top of a resource the platform provides. An overlay works"
                        + " until the upgrade that changes the resource underneath it, which is"
                        + " months later and done by somebody who has never heard of this."))
                .toList();
    }

    /**
     * Whether one written path would sit on top of a platform resource.
     *
     * <p>The one permitted leaf is permitted precisely because it is a leaf: a node <em>under</em>
     * an Adobe extension point is what the extension point is for, and a node <em>at</em> one
     * replaces it.</p>
     *
     * @param path the repository path the package would write
     * @return whether it shadows something
     */
    public static boolean shadows(String path) {
        if (PERMITTED_LEAF.equals(path) || path.startsWith(PERMITTED_LEAF + "/")) {
            return false;
        }
        return PLATFORM_TREES.stream()
                .anyMatch(tree -> path.equals(tree) || path.startsWith(tree + "/"));
    }

    private static List<PolicyFinding> filterFindings(Sources sources) {
        final List<String> declared = rootsIn(sources.structure());
        final List<PolicyFinding> findings = new ArrayList<>();
        rootsIn(sources.filter()).forEach(rule -> {
            if (declared.stream().noneMatch(root -> rule.equals(root)
                    || rule.startsWith(root + "/"))) {
                findings.add(PolicyFinding.inFile(FILTER_FILE, FILTER_TOO_WIDE, rule
                        + " is outside every root the structure package declares, so installing"
                        + " this writes somewhere nobody said it would"));
            }
            if (declared.contains(rule) && shadows(rule)) {
                findings.add(PolicyFinding.inFile(FILTER_FILE, FILTER_REMOVES_MORE, rule
                        + " is a platform tree rather than this product's leaf inside it, so"
                        + " uninstalling would take every other product's entry with it"));
            }
        });
        return findings;
    }

    /**
     * Every root one filter declares.
     *
     * @param filter the filter document
     * @return the roots, in the order the document declares them
     */
    public static List<String> rootsIn(Path filter) {
        if (!Files.isRegularFile(filter)) {
            return List.of();
        }
        final List<String> roots = new ArrayList<>();
        final String held = read(filter);
        // Two spellings because there are two documents: a package's own filter states its roots
        // as an attribute, and the structure package states them to the packaging plugin as an
        // element. Reading both here is what lets one check compare a package with the roots it
        // was given, whichever way each of them was written.
        final Matcher attributes = Pattern.compile("<filter\\s+root=\"([^\"]+)\"").matcher(held);
        while (attributes.find()) {
            roots.add(attributes.group(1));
        }
        final Matcher elements = Pattern.compile("<root>([^<]+)</root>").matcher(held);
        while (elements.find()) {
            roots.add(elements.group(1).strip());
        }
        return List.copyOf(roots);
    }

    /**
     * Every repository path the package's own content would write.
     *
     * <p>Read from the source tree rather than from a built package, because a path that shadows
     * something is a path somebody typed and this is where they typed it — and a check that only
     * looked at the built artifact would say the same thing several minutes later.</p>
     *
     * <p>A directory carrying nothing of its own is not one of them. Reaching a leaf several levels
     * inside an extension point means the tree between has to exist in the source, and those levels
     * carry no content and become no node — the package writes what its content declares, and a
     * check that counted empty directories would report every ancestor of every permitted leaf.</p>
     *
     * @param content the directory the content sits in
     * @return the paths, in ascending order
     */
    public static List<String> writtenPaths(Path content) {
        if (!Files.isDirectory(content)) {
            return List.of();
        }
        try (Stream<Path> held = Files.walk(content)) {
            return held.filter(Files::isDirectory)
                    .filter(OverlayAudit::carriesContent)
                    .map(content::relativize)
                    .map(relative -> "/" + relative.toString().replace('\\', '/'))
                    .filter(path -> !"/".equals(path))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static boolean carriesContent(Path directory) {
        try (Stream<Path> held = Files.list(directory)) {
            return held.anyMatch(Files::isRegularFile);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
