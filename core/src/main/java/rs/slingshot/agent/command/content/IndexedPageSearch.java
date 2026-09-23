// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import rs.slingshot.agent.command.DeclaredQuery;

/**
 * The pages under one root that use one template, asked of the page indexes that can answer it.
 *
 * <p>Which indexes those are is read from the indexes' own definitions, because a deployment's
 * index set is its operator's to change: Adobe's page index indexes the template on some
 * environments and not on others, and a site's own search index often does where it does not. An
 * index is asked only about the subtrees it serves, and only where its definition indexes the
 * template on pages; the statement names it in its own option clause, so the repository is held to
 * that index rather than left to choose one or to walk. What no index serves is left for the caller
 * to walk within its budgets.</p>
 *
 * <p>Every value in the statement is written so the query grammar cannot be left: the template is
 * a string with its quotes doubled, a subtree is a path refused where it holds a closing bracket,
 * and an index name is the definition's own node name, used only where it is made of letters,
 * digits, hyphens and underscores, and written in brackets - without them the parser reads a
 * versioned name such as {@code exampleIndex-custom-3} as ending at its first hyphen.</p>
 */
final class IndexedPageSearch {

    /** Where the repository keeps its index definitions. */
    static final String INDEXES = "/oak:index";

    /** The rule an index keeps for pages. */
    private static final String PAGE_RULE = "indexRules/cq:Page/properties";

    /** The property the statement filters on, as an index definition names it. */
    static final String TEMPLATE_PROPERTY = "jcr:content/cq:template";

    /** The statement's shape, with the subtree, the template and the index still to be written. */
    static final String STATEMENT = "SELECT [jcr:path] FROM [cq:Page] AS page"
            + " WHERE ISDESCENDANTNODE(page, [%s]) AND page.[jcr:content/cq:template] = '%s'"
            + " OPTION(INDEX NAME [%s])";

    /** The statement as the query coverage policy declares it. */
    static final DeclaredQuery DECLARED = new DeclaredQuery("find-pages-by-template", STATEMENT,
            List.of("/content"), List.of("jcr:primaryType", "jcr:content/cq:template"),
            "find_pages_by_template");

    /** What an index name may be made of before it is written into a statement. */
    private static final Pattern INDEX_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    /** An index name's family and its two version numbers, the product's and the customer's. */
    private static final Pattern VERSIONED = Pattern.compile(
            "(.*?)(?:-(\\d+))?(?:-custom-(\\d+))?");

    /** The group of a versioned name holding the product's version. */
    private static final int PRODUCT_VERSION = 2;

    /** The group of a versioned name holding the customer's version. */
    private static final int CUSTOMER_VERSION = 3;

    /** The language the statement is written in. */
    private static final String LANGUAGE = "JCR-SQL2";

    /** What asks the repository for a statement's plan instead of its answer. */
    private static final String EXPLAIN = "EXPLAIN ";

    /** The column an explained statement answers its plan in. */
    private static final String PLAN = "plan";

    /** Orders two names of one family by the product's version and then the customer's. */
    private static final Comparator<String> VERSION = Comparator
            .comparingLong((String name) -> versionOf(name, PRODUCT_VERSION))
            .thenComparingLong(name -> versionOf(name, CUSTOMER_VERSION));

    private IndexedPageSearch() {
    }

    /**
     * One subtree an index serves, and the index that serves it.
     *
     * @param index the index's own name
     * @param path the subtree it is asked about
     */
    record Coverage(String index, String path) {
    }

    /** What asking the indexes produced. */
    sealed interface Outcome permits Found, OverBudget, Unindexed {
    }

    /**
     * Every page the indexes answered with, and the subtrees they answered for.
     *
     * @param pages the pages, in the indexes' own order
     * @param covered the subtrees whose pages are all among them, which nobody has to walk
     */
    record Found(List<Resource> pages, List<String> covered) implements Outcome {

        /** Holds the lists apart from whatever produced them. */
        Found {
            pages = List.copyOf(pages);
            covered = List.copyOf(covered);
        }
    }

    /** More pages than may be examined, which is refused rather than trimmed. */
    record OverBudget() implements Outcome {
    }

    /**
     * No index serves any of the root, so nothing was run.
     *
     * @param detail why
     */
    record Unindexed(String detail) implements Outcome {
    }

    /**
     * Asks every index that serves part of one root which of its pages use one template.
     *
     * @param resolver the caller's own resolver, so the answer is what the caller can read
     * @param root the subtree
     * @param template the template's path
     * @param budget how many pages may be examined
     * @return the pages and what they cover, or why no index was asked
     */
    static Outcome search(ResourceResolver resolver, String root, String template, long budget) {
        try {
            final List<Coverage> coverages = coverages(resolver, root);
            if (coverages.isEmpty()) {
                return new Unindexed("no index here indexes " + TEMPLATE_PROPERTY
                        + " on pages anywhere under " + root);
            }
            final List<Resource> pages = new ArrayList<>();
            final List<String> covered = new ArrayList<>();
            for (final Coverage coverage : coverages) {
                final Optional<Outcome> stopped = asked(resolver, coverage, template, budget,
                        pages);
                if (stopped.isPresent()) {
                    return stopped.get();
                }
                covered.add(coverage.path());
            }
            return new Found(pages, covered);
        } catch (final SlingException | UnsupportedOperationException unanswerable) {
            // A statement the repository rejects - an unknown node type, an unknown index - and a
            // resource provider that answers no queries at all are both a place no index is.
            return new Unindexed("the repository will not run this statement here: "
                    + unanswerable.getMessage());
        }
    }

    /**
     * Asks one index about one subtree, adding what it found.
     *
     * @return nothing where it answered, or why the whole search stops
     */
    private static Optional<Outcome> asked(ResourceResolver resolver, Coverage coverage,
                                           String template, long budget, List<Resource> pages) {
        final String statement = String.format(java.util.Locale.ROOT, STATEMENT, coverage.path(),
                template.replace("'", "''"), coverage.index());
        final String plan = planOf(resolver, statement);
        if (DeclaredQuery.permitted(List.of(DECLARED), STATEMENT, plan)
                instanceof final DeclaredQuery.Refused refused) {
            return Optional.of(new Unindexed(refused.detail()));
        }
        // A repository that explains nothing leaves the definition read above, and the option
        // clause, as what holds the statement to the index; one that does explain must name it.
        if (!plan.isEmpty() && !plan.contains(coverage.index())) {
            return Optional.of(new Unindexed("the plan is not answered from " + coverage.index()
                    + ": " + plan));
        }
        // The subtree's own top is a page too, and a descendant condition leaves it out.
        Optional.ofNullable(resolver.getResource(coverage.path())).ifPresent(pages::add);
        final Iterator<Resource> found = resolver.findResources(statement, LANGUAGE);
        while (found.hasNext()) {
            if (pages.size() >= budget) {
                return Optional.of(new OverBudget());
            }
            pages.add(found.next());
        }
        return Optional.empty();
    }

    /**
     * Which subtrees of one root an index that indexes the template serves, and which index.
     *
     * <p>Only the latest version of each index family is considered, because that is the one the
     * repository keeps current. A subtree inside another covered subtree is left to the outer one.
     * Where the definitions cannot be read at all, nothing is covered.</p>
     *
     * @param resolver the caller's own resolver
     * @param root the subtree the caller asked about
     * @return the coverages, each subtree once, in path order
     */
    static List<Coverage> coverages(ResourceResolver resolver, String root) {
        final Resource indexes = resolver.getResource(INDEXES);
        if (indexes == null || root.indexOf(']') >= 0) {
            return List.of();
        }
        final Map<String, Coverage> byPath = new LinkedHashMap<>();
        for (final Resource index : latestCovering(indexes)) {
            for (final String served : servedPaths(index.getValueMap())) {
                coverageOf(index.getName(), served, root)
                        .ifPresent(coverage -> byPath.putIfAbsent(coverage.path(), coverage));
            }
        }
        return byPath.values().stream()
                .filter(coverage -> byPath.keySet().stream().noneMatch(outer ->
                        !outer.equals(coverage.path()) && within(coverage.path(), outer)))
                .sorted(Comparator.comparing(Coverage::path))
                .toList();
    }

    /** The latest version of every index family whose definition indexes the template. */
    private static List<Resource> latestCovering(Resource indexes) {
        final Map<String, Resource> latest = new LinkedHashMap<>();
        for (final Resource index : indexes.getChildren()) {
            if (INDEX_NAME.matcher(index.getName()).matches() && indexesTemplate(index)) {
                latest.merge(familyOf(index.getName()), index, (held, candidate) ->
                        VERSION.compare(held.getName(), candidate.getName()) >= 0
                                ? held : candidate);
            }
        }
        return List.copyOf(latest.values());
    }

    /** What of one root one served subtree covers: the root itself, the subtree, or nothing. */
    private static Optional<Coverage> coverageOf(String index, String served, String root) {
        if (within(root, served)) {
            return Optional.of(new Coverage(index, root));
        }
        return within(served, root) && served.indexOf(']') < 0
                ? Optional.of(new Coverage(index, served)) : Optional.empty();
    }

    /** Whether one index is a live full-text index whose page rule indexes the template. */
    private static boolean indexesTemplate(Resource index) {
        if (!"lucene".equals(index.getValueMap().get("type", ""))) {
            return false;
        }
        final Resource properties = index.getChild(PAGE_RULE);
        if (properties == null) {
            return false;
        }
        for (final Resource property : properties.getChildren()) {
            final ValueMap values = property.getValueMap();
            if (TEMPLATE_PROPERTY.equals(values.get("name", ""))
                    && values.get("propertyIndex", false)) {
                return true;
            }
        }
        return false;
    }

    /** The subtrees an index answers queries about, which is everything where it names none. */
    private static List<String> servedPaths(ValueMap definition) {
        final String[] query = definition.get("queryPaths", new String[0]);
        if (query.length > 0) {
            return List.of(query);
        }
        final String[] included = definition.get("includedPaths", new String[0]);
        return included.length > 0 ? List.of(included) : List.of("/");
    }

    /**
     * Whether one path is another or lies beneath it.
     *
     * @param path the path
     * @param ancestor the one it may lie under
     * @return whether it does
     */
    static boolean within(String path, String ancestor) {
        return "/".equals(ancestor) || path.equals(ancestor) || path.startsWith(ancestor + "/");
    }

    /** An index name without its versions, which is what its versions have in common. */
    private static String familyOf(String name) {
        final Matcher matched = VERSIONED.matcher(name);
        return matched.matches() ? matched.group(1) : name;
    }

    private static long versionOf(String name, int group) {
        final Matcher matched = VERSIONED.matcher(name);
        return matched.matches() && matched.group(group) != null
                ? Long.parseLong(matched.group(group)) : 0L;
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
