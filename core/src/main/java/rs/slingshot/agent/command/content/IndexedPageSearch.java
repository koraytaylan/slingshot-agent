// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.DeclaredQuery;

/**
 * The pages under one root that use one template, asked of Adobe's own page index.
 *
 * <p>The statement names the index it must be answered from in its own option clause, and is
 * explained before it is run: a plan that walks the repository, or that is answered by any index
 * other than the one named, is not run at all. A caller who asks which pages use a template is
 * about to change every one of them, and producing the list must not itself be what takes the
 * instance down.</p>
 *
 * <p>Both values in the statement are written as literals the query grammar cannot leave: the
 * template is a string with its quotes doubled, and the root is a path, which holds no closing
 * bracket in any repository this agent runs against and is refused where it would.</p>
 */
final class IndexedPageSearch {

    /** The index every run of this search must be answered from. */
    static final String INDEX = "cqPageLucene";

    /** The statement's shape, with the root and the template still to be written in. */
    static final String STATEMENT = "SELECT [jcr:path] FROM [cq:Page] AS page"
            + " WHERE ISDESCENDANTNODE(page, [%s]) AND page.[jcr:content/cq:template] = '%s'"
            + " OPTION(INDEX NAME " + INDEX + ")";

    /** The statement as the query coverage policy declares it. */
    static final DeclaredQuery DECLARED = new DeclaredQuery("find-pages-by-template", STATEMENT,
            List.of("/content"), List.of("jcr:primaryType", "jcr:content/cq:template"),
            "find_pages_by_template");

    /** The language the statement is written in. */
    private static final String LANGUAGE = "JCR-SQL2";

    /** What asks the repository for a statement's plan instead of its answer. */
    private static final String EXPLAIN = "EXPLAIN ";

    /** The column an explained statement answers its plan in. */
    private static final String PLAN = "plan";

    private IndexedPageSearch() {
    }

    /** What asking the index produced. */
    sealed interface Outcome permits Found, OverBudget, Unindexed {
    }

    /**
     * Every page the index answered with.
     *
     * @param pages the pages, in the index's own order
     */
    record Found(List<Resource> pages) implements Outcome {

        /** Holds the pages apart from whatever produced them. */
        Found {
            pages = List.copyOf(pages);
        }
    }

    /** More pages than may be examined, which is refused rather than trimmed. */
    record OverBudget() implements Outcome {
    }

    /**
     * The index cannot answer here, so nothing was run.
     *
     * @param detail why, naming the plan where there was one
     */
    record Unindexed(String detail) implements Outcome {
    }

    /**
     * Asks the page index which pages under one root use one template.
     *
     * @param resolver the caller's own resolver, so the answer is what the caller can read
     * @param root the subtree
     * @param template the template's path
     * @param budget how many pages may be examined
     * @return the pages, or why the index was not asked
     */
    static Outcome search(ResourceResolver resolver, String root, String template, long budget) {
        if (root.indexOf(']') >= 0) {
            return new Unindexed(root + " cannot be written as a path literal");
        }
        final String statement = String.format(java.util.Locale.ROOT, STATEMENT, root,
                template.replace("'", "''"));
        try {
            final String plan = planOf(resolver, statement);
            if (DeclaredQuery.permitted(List.of(DECLARED), STATEMENT, plan)
                    instanceof final DeclaredQuery.Refused refused) {
                return new Unindexed(refused.detail());
            }
            if (!plan.contains(INDEX)) {
                return new Unindexed("the plan is not answered from " + INDEX + ": " + plan);
            }
            final List<Resource> pages = new ArrayList<>();
            final Iterator<Resource> found = resolver.findResources(statement, LANGUAGE);
            while (found.hasNext()) {
                if (pages.size() >= budget) {
                    return new OverBudget();
                }
                pages.add(found.next());
            }
            return new Found(pages);
        } catch (final SlingException | UnsupportedOperationException unanswerable) {
            // A statement the repository rejects - an unknown node type, an unknown index - and a
            // resource provider that answers no queries at all are both a place this index is not.
            return new Unindexed("the repository will not run this statement here: "
                    + unanswerable.getMessage());
        }
    }

    /**
     * The plan the repository would answer one statement with, without running it.
     *
     * @param resolver the caller's own resolver
     * @param statement the statement
     * @return the plan, or nothing where the repository explains nothing
     */
    private static String planOf(ResourceResolver resolver, String statement) {
        final String explained = EXPLAIN + statement;
        final Iterator<Map<String, Object>> rows = resolver.queryResources(explained, LANGUAGE);
        return rows.hasNext() ? String.valueOf(rows.next().get(PLAN)) : "";
    }
}
