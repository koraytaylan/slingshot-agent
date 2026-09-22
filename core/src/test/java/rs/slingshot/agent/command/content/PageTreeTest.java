// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rs.slingshot.agent.command.ReadOnlyResolver;

/**
 * The walk every page search goes through, which opens only what can hold a page.
 */
@ExtendWith(SlingContextExtension.class)
final class PageTreeTest {

    /** A budget far above every tree here that holds only pages and folders. */
    private static final long ROOMY = 1_000;

    /** How many nodes each tree the walk must not open is given. */
    private static final int WIDE = 300;

    /** A budget below any of those trees, and above the pages beside them. */
    private static final long NARROW = 40;

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("a site under folders is reached, and the trees that are not sites are passed over")
    void aSiteUnderFoldersIsReached() {
        folder("/content");
        folder("/content/brand");
        page("/content/brand/site");
        page("/content/brand/site/en");
        folder("/content/dam");
        page("/content/dam/looks-like-a-page");
        folder("/content/launches");
        page("/content/launches/copy");
        folder("/content/experience-fragments");
        page("/content/experience-fragments/header");
        assertEquals(List.of("/content/brand/site", "/content/brand/site/en"),
                visited("/", ROOMY));
        assertEquals(List.of("/content/brand/site", "/content/brand/site/en"),
                visited("/content", ROOMY));
    }

    @Test
    @DisplayName("an anchor inside a tree that is not a site is still walked, because it was named")
    void aNamedAnchorIsWalked() {
        folder("/content/experience-fragments");
        page("/content/experience-fragments/header");
        assertEquals(List.of("/content/experience-fragments/header"),
                visited("/content/experience-fragments", ROOMY));
    }

    @Test
    @DisplayName("content nodes and folders inside a page are never opened")
    void onlyPagesAreOpenedBelowAPage() {
        page("/content/site");
        page("/content/site/child");
        for (int node = 0; node < WIDE; node = node + 1) {
            sling.create().resource("/content/site/jcr:content/root/item" + node);
        }
        page("/content/site/jcr:content/root/not-a-real-page");
        folder("/content/site/stray");
        page("/content/site/stray/hidden");
        assertEquals(List.of("/content/site", "/content/site/child"),
                visited("/content/site", NARROW));
    }

    @Test
    @DisplayName("a walk past its budget stops and says so")
    void aWalkPastItsBudgetSaysSo() {
        page("/content/site");
        for (int node = 0; node < WIDE; node = node + 1) {
            page("/content/site/page" + node);
        }
        assertEquals(PageTree.Walk.EXHAUSTED, PageTree.pagesUnder(readOnly(),
                readOnly().getResource("/content/site"), NARROW, page -> { }));
    }

    private List<String> visited(String anchor, long budget) {
        final Resource root = readOnly().getResource(anchor);
        final List<String> pages = new ArrayList<>();
        assertEquals(PageTree.Walk.FINISHED, PageTree.pagesUnder(readOnly(), root, budget,
                page -> pages.add(page.getPath())), "the walk ran out of its budget");
        return pages.stream().sorted().toList();
    }

    private ResourceResolver readOnly() {
        return ReadOnlyResolver.around(sling.resourceResolver());
    }

    private void page(String path) {
        sling.create().resource(path, Map.of(ListChildPagesHandler.TYPE_PROPERTY,
                ListChildPagesHandler.PAGE_TYPE));
    }

    private void folder(String path) {
        sling.create().resource(path, Map.of(ListChildPagesHandler.TYPE_PROPERTY,
                "sling:OrderedFolder"));
    }
}
