// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The page index search, driven over a resolver whose plans and answers the suite scripts.
 *
 * <p>What is proved is that the statement names its index in its own option clause, that a value
 * cannot leave the literal it is written into, and that a plan answered by anything but that index
 * runs nothing at all.</p>
 */
final class IndexedPageSearchTest {

    /** A plan answered from the page index. */
    private static final String INDEXED =
            "[cq:Page] as [page] /* lucene:cqPageLucene(/oak:index/cqPageLucene) */";

    /** How many pages the budget admits here. */
    private static final long BUDGET = 2;

    @Test
    @DisplayName("the statement names its index, and a quote in the template stays inside its literal")
    void thestatementNamesItsIndex() {
        final Scripted resolver = new Scripted(INDEXED, 1);
        final IndexedPageSearch.Found found = assertInstanceOf(IndexedPageSearch.Found.class,
                IndexedPageSearch.search(resolver.proxy(), "/content/site",
                        "/conf/t' OR 'x'='x", BUDGET));
        assertEquals(1, found.pages().size());
        final String issued = resolver.issued.getLast();
        assertTrue(issued.endsWith(" OPTION(INDEX NAME cqPageLucene)"), issued);
        assertTrue(issued.contains("= '/conf/t'' OR ''x''=''x'"), issued);
        assertEquals("EXPLAIN " + issued, resolver.issued.getFirst(),
                "the statement was run without being explained first");
    }

    @Test
    @DisplayName("a plan that walks, or is answered by another index, runs nothing")
    void anunindexedPlanRunsNothing() {
        for (final String plan : List.of("[cq:Page] as [page] /* traverse \"/content//*\" */",
                "[cq:Page] as [page] /* property:cqTemplate */")) {
            final Scripted resolver = new Scripted(plan, 1);
            assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                    resolver.proxy(), "/content/site", "/conf/t", BUDGET), plan);
            assertEquals(1, resolver.issued.size(), "the statement ran under plan " + plan);
        }
    }

    @Test
    @DisplayName("more pages than may be examined, a root that cannot be written and a refusal fall back")
    void theotherOutcomes() {
        assertInstanceOf(IndexedPageSearch.OverBudget.class, IndexedPageSearch.search(
                new Scripted(INDEXED, BUDGET + 1).proxy(), "/content/site", "/conf/t", BUDGET));
        assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                new Scripted(INDEXED, 1).proxy(), "/content/a]b", "/conf/t", BUDGET));
        assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(refusing(),
                "/content/site", "/conf/t", BUDGET));
    }

    /** A resolver whose repository rejects every statement, as one without the page type does. */
    private static ResourceResolver refusing() {
        return (ResourceResolver) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {ResourceResolver.class}, (proxy, method, arguments) -> {
                    throw new QuerySyntaxException("unknown node type", "", "JCR-SQL2");
                });
    }

    @Test
    @DisplayName("an index that does not index the template here is not asked at all")
    void anuncoveringIndexIsNotAsked() {
        final Scripted resolver = new Scripted(INDEXED, 1, "jcr:content/jcr:title");
        assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                resolver.proxy(), "/content/site", "/conf/t", BUDGET));
        assertEquals(List.of(), resolver.issued, "a statement was issued to an index that walks");
    }

    /** A resolver that explains every statement with one plan and answers with some pages. */
    private static final class Scripted {

        private final String plan;
        private final long answered;
        private final String indexed;
        private final List<String> issued = new ArrayList<>();

        Scripted(String plan, long answered) {
            this(plan, answered, IndexedPageSearch.TEMPLATE_PROPERTY);
        }

        Scripted(String plan, long answered, String indexed) {
            this.plan = plan;
            this.answered = answered;
            this.indexed = indexed;
        }

        private Resource definition() {
            final Resource property = (Resource) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[] {Resource.class},
                    (proxy, method, arguments) -> new org.apache.sling.api.wrappers.ValueMapDecorator(
                            Map.<String, Object>of("name", indexed, "propertyIndex", true)));
            return (Resource) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[] {Resource.class},
                    (proxy, method, arguments) -> List.of(property));
        }

        ResourceResolver proxy() {
            final Resource page = (Resource) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[] {Resource.class},
                    (proxy, method, arguments) -> "/content/site/page");
            return (ResourceResolver) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {ResourceResolver.class}, (proxy, method, arguments) -> {
                        if ("getResource".equals(method.getName())) {
                            return definition();
                        }
                        issued.add((String) arguments[0]);
                        return switch (method.getName()) {
                            case "queryResources" -> List.of(Map.<String, Object>of("plan", plan))
                                    .iterator();
                            case "findResources" -> java.util.Collections.nCopies(
                                    (int) answered, page).iterator();
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }
}
