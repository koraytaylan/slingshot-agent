// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * The pages under one anchor, reached without opening anything that cannot hold one.
 *
 * <p>Sites live under {@code /content}, reached through folders until the first page, and every
 * page below that is a child of another page. So the walk opens folders only while no page is
 * above them, opens pages always, and opens nothing else: a page's {@code jcr:content}, where its
 * components and every other node an author places are, is never entered, and neither is a
 * folder inside a page. The trees under {@code /content} that are not sites are passed over on the
 * way; an anchor the caller placed inside one is still walked, because they named it.</p>
 *
 * <p>Every node the walk reads is counted against the discovery budget, including the ones it
 * passes over, and a walk that runs past it stops and says so rather than answering with the part
 * it reached.</p>
 */
final class PageTree {

    /** Where sites are, and where an anchor above it starts. */
    static final String CONTENT = "/content";

    /**
     * The trees under {@code /content} that hold no site pages, passed over on the way to one.
     *
     * <p>Assets, tags, experience fragments and projects are not sites; a launch is a copy of one,
     * and forms, screens, campaigns and communities keep their own page-shaped nodes that are not
     * the pages somebody searching a site means.</p>
     */
    static final List<String> NOT_SITES = List.of(CONTENT + "/dam", CONTENT + "/cq:tags",
            CONTENT + "/experience-fragments", CONTENT + "/projects", CONTENT + "/launches",
            CONTENT + "/forms", CONTENT + "/screens", CONTENT + "/campaigns",
            CONTENT + "/communities", CONTENT + "/usergenerated", CONTENT + "/catalogs");

    /** The folder types a site may sit under before its first page. */
    private static final List<String> FOLDER_TYPES =
            List.of("sling:Folder", "sling:OrderedFolder", "nt:folder");

    private final long budget;
    private final AtomicLong examined = new AtomicLong();

    private PageTree(long budget) {
        this.budget = budget;
    }

    /** How a walk ended. */
    enum Walk {
        /** Every page under the anchor was visited. */
        FINISHED,
        /** The walk ran past its budget and stopped before it had visited every page. */
        EXHAUSTED
    }

    /**
     * Visits every page under one anchor, the anchor included when it is a page.
     *
     * @param resolver the caller's read-only resolver
     * @param anchor the node the caller named
     * @param budget how many nodes the walk may read
     * @param visitor what is done with each page
     * @return whether every page was visited
     */
    static Walk pagesUnder(ResourceResolver resolver, Resource anchor, long budget,
                           Consumer<Resource> visitor) {
        final Optional<Resource> start = "/".equals(anchor.getPath())
                ? Optional.ofNullable(resolver.getResource(CONTENT)) : Optional.of(anchor);
        final PageTree tree = new PageTree(budget);
        start.ifPresent(top -> tree.walk(top, visitor));
        return tree.examined.get() > budget ? Walk.EXHAUSTED : Walk.FINISHED;
    }

    private void walk(Resource top, Consumer<Resource> visitor) {
        final Deque<Level> pending = new ArrayDeque<>();
        examined.incrementAndGet();
        Optional<Resource> next = Optional.of(top);
        while (next.isPresent() && examined.get() <= budget) {
            final Resource current = next.orElseThrow();
            final Opens opens = isPage(current) || level(pending) == Opens.PAGES
                    ? Opens.PAGES : Opens.FOLDERS_AND_PAGES;
            if (isPage(current)) {
                visitor.accept(current);
            }
            pending.push(new Level(current.listChildren(), opens));
            next = nextOpened(pending);
        }
    }

    private Optional<Resource> nextOpened(Deque<Level> pending) {
        while (!pending.isEmpty() && examined.get() <= budget) {
            final Level level = pending.peek();
            final Optional<Resource> child = level.children().hasNext()
                    ? Optional.of(level.children().next()) : Optional.empty();
            if (child.isEmpty()) {
                pending.pop();
            } else if (opens(child.orElseThrow(), level)) {
                return child;
            }
        }
        return Optional.empty();
    }

    /** Reads one child, and answers whether the walk goes into it. */
    private boolean opens(Resource child, Level level) {
        examined.incrementAndGet();
        if (NOT_SITES.contains(child.getPath())) {
            return false;
        }
        return isPage(child) || level.opens() == Opens.FOLDERS_AND_PAGES
                && FOLDER_TYPES.contains(ChildListingHandler.typeOf(child));
    }

    private static Opens level(Deque<Level> pending) {
        return pending.isEmpty() ? Opens.FOLDERS_AND_PAGES : pending.peek().opens();
    }

    private static boolean isPage(Resource resource) {
        return ListChildPagesHandler.PAGE_TYPE.equals(ChildListingHandler.typeOf(resource));
    }

    /** What the walk may go into below one node. */
    private enum Opens {
        /** No page is at or above the node, so a folder may still lead to a site. */
        FOLDERS_AND_PAGES,
        /** A page is at or above the node, so only its child pages are part of the tree. */
        PAGES
    }

    /**
     * The children of one opened node still to be read, and what the walk may go into.
     *
     * @param children what is left of its children
     * @param opens what may be opened among them
     */
    private record Level(Iterator<Resource> children, Opens opens) {
    }
}
