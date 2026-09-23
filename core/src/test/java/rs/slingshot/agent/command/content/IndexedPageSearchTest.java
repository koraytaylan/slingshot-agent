// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The page index search, over index definitions shaped like a real deployment's and a repository
 * whose plans and answers the suite scripts.
 *
 * <p>What is proved is that only an index whose own definition indexes the template is asked, only
 * about the subtrees it serves, by a statement naming it in its own option clause; that a value
 * cannot leave the literal it is written into; and that a plan answered by anything else runs
 * nothing at all.</p>
 */
@ExtendWith(SlingContextExtension.class)
final class IndexedPageSearchTest {

    /** How many pages the budget admits here. */
    private static final long BUDGET = 5;

    private final SlingContext sling = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Test
    @DisplayName("each covered subtree is asked of the latest index serving it, by name")
    void eachSubtreeIsAskedOfItsIndex() {
        definitions();
        final Scripted resolver = new Scripted("");
        final IndexedPageSearch.Found found = assertInstanceOf(IndexedPageSearch.Found.class,
                IndexedPageSearch.search(resolver.resolver(), "/content", "/conf/t' OR 'x'='x",
                        BUDGET));
        assertEquals(List.of("/content/brand-a", "/content/site"), found.covered(),
                "the subtrees answered are not exactly the ones the indexes serve");
        final List<String> run = resolver.issued.stream()
                .filter(statement -> !statement.startsWith("EXPLAIN")).toList();
        assertEquals(2, run.size());
        assertTrue(run.getFirst().endsWith(" OPTION(INDEX NAME [otherIndex-custom-2])"),
                run.getFirst());
        assertTrue(run.getLast().endsWith(" OPTION(INDEX NAME [exampleIndex-custom-3])"),
                "an older version of the index, or an index that does not index the template,"
                        + " was named: " + run.getLast());
        assertTrue(run.getLast().contains("= '/conf/t'' OR ''x''=''x'"), run.getLast());
        assertEquals(List.of(new IndexedPageSearch.Coverage("exampleIndex-custom-3",
                        "/content/site/en")),
                IndexedPageSearch.coverages(resolver.resolver(), "/content/site/en"),
                "a root inside a served subtree is not asked of that subtree's index as itself");
    }

    @Test
    @DisplayName("a plan that walks, or is answered by another index, runs nothing")
    void anunindexedPlanRunsNothing() {
        definitions();
        for (final String plan : List.of("[cq:Page] as [page] /* traverse \"/content//*\" */",
                "[cq:Page] as [page] /* property:cqTemplate */")) {
            final Scripted resolver = new Scripted(plan);
            assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                    resolver.resolver(), "/content/site", "/conf/t", BUDGET), plan);
            assertTrue(resolver.issued.stream().allMatch(issued -> issued.startsWith("EXPLAIN")),
                    "a statement ran under plan " + plan);
        }
    }

    @Test
    @DisplayName("no covering index, too many pages, and a refusing repository are each told apart")
    void theotherOutcomes() {
        assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                new Scripted("").resolver(), "/content/site", "/conf/t", BUDGET),
                "a repository with no index definitions was asked");
        definitions();
        final Scripted plenty = new Scripted("");
        plenty.answered = BUDGET + 1;
        assertInstanceOf(IndexedPageSearch.OverBudget.class, IndexedPageSearch.search(
                plenty.resolver(), "/content/site", "/conf/t", BUDGET));
        assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                new Scripted("").resolver(), "/content/uncovered", "/conf/t", BUDGET));
        final Scripted refusing = new Scripted("");
        refusing.refuses = true;
        assertInstanceOf(IndexedPageSearch.Unindexed.class, IndexedPageSearch.search(
                refusing.resolver(), "/content/site", "/conf/t", BUDGET));
    }

    /**
     * Three families: one indexing the template in two versions, one serving a brand, and one
     * that indexes only the title.
     */
    private void definitions() {
        index("exampleIndex-custom-2", "/content/site");
        index("exampleIndex-custom-3", "/content/site");
        index("otherIndex-custom-2", "/content/brand-a");
        sling.create().resource("/oak:index/titleOnly", Map.of("type", "lucene"));
        sling.create().resource("/oak:index/titleOnly/indexRules/cq:Page/properties/jcrTitle",
                Map.of("name", "jcr:content/jcr:title", "propertyIndex", true));
        sling.create().resource("/content/site", Map.of());
        sling.create().resource("/content/brand-a", Map.of());
    }

    private void index(String name, String served) {
        sling.create().resource("/oak:index/" + name, Map.of("type", "lucene",
                "queryPaths", new String[] {served}));
        sling.create().resource("/oak:index/" + name + "/indexRules/cq:Page/properties/cqTemplate",
                Map.of("name", IndexedPageSearch.TEMPLATE_PROPERTY, "propertyIndex", true));
    }

    /** The mock repository, with every statement explained by one plan and answered with pages. */
    private final class Scripted {

        private final String plan;
        private final List<String> issued = new ArrayList<>();
        private long answered = 1;
        private boolean refuses;

        Scripted(String plan) {
            this.plan = plan;
        }

        org.apache.sling.api.resource.ResourceResolver resolver() {
            return new ResourceResolverWrapper(sling.resourceResolver()) {
                @Override
                public Iterator<Map<String, Object>> queryResources(String query,
                                                                    String language) {
                    issued.add(query);
                    refuse(query);
                    return plan.isEmpty() ? List.<Map<String, Object>>of().iterator()
                            : List.<Map<String, Object>>of(Map.of("plan", plan)).iterator();
                }

                @Override
                public Iterator<Resource> findResources(String query, String language) {
                    issued.add(query);
                    refuse(query);
                    final List<Resource> pages = new ArrayList<>();
                    for (long page = 0; page < answered; page++) {
                        pages.add(sling.create().resource("/content/site/page-" + issued.size()
                                + "-" + page, Map.of()));
                    }
                    return pages.iterator();
                }
            };
        }

        private void refuse(String query) {
            if (refuses) {
                throw new QuerySyntaxException("unknown node type", query, "JCR-SQL2");
            }
        }
    }
}
