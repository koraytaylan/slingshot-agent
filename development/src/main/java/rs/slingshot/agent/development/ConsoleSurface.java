// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.development;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything of this console a request can reach, derived from the package rather than from a list.
 *
 * <p>The console is where this repository's security model meets a person's browser session for the
 * first time, and a data source is a servlet like any other. The point of deriving the surface
 * rather than listing it is that a page somebody adds next year is covered by the proof the day it
 * is added, instead of on the day somebody remembers to add a line here — which, for the resource
 * that gets forgotten, is never.</p>
 *
 * <p>Everything here reads a screen. A console that could change something would be a second way to
 * do what the routes do, with a second authorization story and a second audit trail, and the first
 * thing that differs between the two is the thing nobody tests — so a resource that would write is
 * refused at the build rather than reviewed.</p>
 */
public final class ConsoleSurface {

    /** Where the console's own resources sit inside the content package. */
    public static final String CONSOLE =
            "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console";

    /** Where the package states which roots it carries. */
    public static final String FILTER = "ui.apps/src/main/content/META-INF/vault/filter.xml";

    /** Where the screens that fill these pages live. */
    public static final String SOURCES = "core/src/main/java/rs/slingshot/agent/console";

    /** Where each page is described for a person. */
    public static final String DOCUMENT = "docs/CONSOLE.md";

    /** The rule a page or a data source no proof reaches is reported under. */
    public static final String UNCOVERED = "uncovered-console-resource";

    /** The rule a console resource the package does not carry is reported under. */
    public static final String OUTSIDE_FILTER = "console-resource-outside-the-filter";

    /** The rule a console resource that would change something is reported under. */
    public static final String STATE_CHANGE = "state-changing-console-resource";

    /** The rule a page the document does not describe is reported under. */
    public static final String UNDOCUMENTED = "undocumented-console-page";

    /** The rule a page the document describes and the package does not carry is reported under. */
    public static final String UNKNOWN_PAGE = "unknown-console-page";

    /** How a page declares the data source that fills it. */
    private static final String DATA_SOURCE = "slingshot-agent/datasource/";

    /** What a screen's class is called, which is how one is found without a list. */
    private static final String SCREEN_SUFFIX = "DataSource.java";

    /** The one screen class that is the gate rather than a screen, and fills no page. */
    private static final String THE_GATE = "ConsoleDataSource.java";

    /** The resource-type words that would make a console resource write something. */
    private static final List<String> WRITES =
            List.of("/form", "/submit", "post", "delete", "/upload");

    private ConsoleSurface() {
    }

    /**
     * One console's whole reachable surface.
     *
     * @param pages every page, by the address an operator reaches it at
     * @param dataSources every data source resource type the pages declare, each once
     * @param screens every class that fills one, by its own name
     */
    public record Surface(List<String> pages, List<String> dataSources, List<String> screens) {

        /** Holds a surface nothing can change afterwards. */
        public Surface {
            pages = List.copyOf(pages);
            dataSources = List.copyOf(dataSources);
            screens = List.copyOf(screens);
        }
    }

    /**
     * What a request can reach, read out of the package's own content.
     *
     * @param root the repository root
     * @return the surface
     */
    public static Surface of(Path root) {
        final List<String> pages = new ArrayList<>();
        final Set<String> dataSources = new LinkedHashSet<>();
        RepositoryTree.filesUnder(root.resolve(CONSOLE), ".content.xml").forEach(file -> {
            final String held = RepositoryTree.text(file);
            pages.add(addressOf(root, file));
            int at = held.indexOf(DATA_SOURCE);
            while (at >= 0) {
                final int end = held.indexOf('"', at);
                dataSources.add(held.substring(at + DATA_SOURCE.length(), end));
                at = held.indexOf(DATA_SOURCE, end);
            }
        });
        final List<String> screens = new ArrayList<>();
        RepositoryTree.filesUnder(root.resolve(SOURCES), SCREEN_SUFFIX).stream()
                .map(file -> String.valueOf(file.getFileName()))
                .filter(name -> !THE_GATE.equals(name))
                .forEach(screens::add);
        return new Surface(pages, List.copyOf(dataSources), screens);
    }

    /**
     * The whole surface, held to everything that has to be true of all of it.
     *
     * @param root the repository root
     * @return one finding per resource that breaks one
     */
    public static PolicyReport across(Path root) {
        final Surface surface = of(root);
        final List<PolicyFinding> findings = new ArrayList<>();
        if (surface.dataSources().size() != surface.screens().size()) {
            findings.add(PolicyFinding.inFile(CONSOLE, UNCOVERED, surface.dataSources().size()
                    + " data sources are declared and " + surface.screens().size() + " screens"
                    + " exist to fill them — " + surface.dataSources() + " against "
                    + surface.screens() + ". One of them is reachable and answered by nothing, or"
                    + " written and reached by nothing."));
        }
        findings.addAll(filterFindings(root, surface));
        findings.addAll(writeFindings(root));
        findings.addAll(documentFindings(root, surface));
        return PolicyReport.of(findings);
    }

    private static List<PolicyFinding> filterFindings(Path root, Surface surface) {
        final String filter = Files.isRegularFile(root.resolve(FILTER))
                ? RepositoryTree.text(root.resolve(FILTER)) : "";
        return surface.pages().stream()
                .filter(page -> !carries(filter, page))
                .map(page -> PolicyFinding.inFile(FILTER, OUTSIDE_FILTER, page + " is reachable and"
                        + " the package does not carry it, so it exists on whatever instance"
                        + " somebody built it on and nowhere else"))
                .toList();
    }

    private static boolean carries(String filter, String page) {
        return filter.lines()
                .filter(line -> line.contains("root="))
                .map(line -> line.substring(line.indexOf("root=\"") + "root=\"".length()))
                .map(line -> line.substring(0, line.indexOf('"')))
                .anyMatch(page::startsWith);
    }

    private static List<PolicyFinding> writeFindings(Path root) {
        final List<PolicyFinding> findings = new ArrayList<>();
        RepositoryTree.filesUnder(root.resolve(CONSOLE), ".content.xml").forEach(file -> {
            final String held = RepositoryTree.text(file);
            WRITES.stream()
                    .filter(held::contains)
                    .forEach(writes -> findings.add(PolicyFinding.inFile(
                            root.relativize(file).toString(), STATE_CHANGE, writes
                                    + " would change something, and every console resource reads."
                                    + " A console that wrote would be a second way to do what the"
                                    + " routes do, with a second authorization story.")));
        });
        return findings;
    }

    private static List<PolicyFinding> documentFindings(Path root, Surface surface) {
        final String document = Files.isRegularFile(root.resolve(DOCUMENT))
                ? RepositoryTree.text(root.resolve(DOCUMENT)) : "";
        final List<PolicyFinding> findings = new ArrayList<>();
        surface.pages().stream()
                .filter(page -> !document.contains(page))
                .map(page -> PolicyFinding.inFile(DOCUMENT, UNDOCUMENTED, page + " is reachable and"
                        + " the document does not say what it shows or who may see it"))
                .forEach(findings::add);
        documentedPages(document).stream()
                .filter(page -> !surface.pages().contains(page))
                .map(page -> PolicyFinding.inFile(DOCUMENT, UNKNOWN_PAGE, page + " is described and"
                        + " the package carries no such page"))
                .forEach(findings::add);
        return findings;
    }

    /**
     * Every page the document describes, read from the document's own addresses.
     *
     * @param document the document's text
     * @return the addresses, in the order it describes them
     */
    public static List<String> documentedPages(String document) {
        final List<String> pages = new ArrayList<>();
        document.lines()
                .filter(line -> line.contains("`/apps/slingshot-agent/content/console"))
                .forEach(line -> {
                    final int at = line.indexOf("`/apps/");
                    final int end = line.indexOf('`', at + 1);
                    pages.add(line.substring(at + 1, end));
                });
        return pages;
    }

    /**
     * The address one page resource is reached at, derived from where it sits in the package.
     *
     * @param root the repository root
     * @param file the page's own content file
     * @return the address
     */
    private static String addressOf(Path root, Path file) {
        final String relative = root.relativize(file.getParent()).toString().replace('\\', '/');
        return relative.substring(relative.indexOf("/jcr_root") + "/jcr_root".length());
    }
}
