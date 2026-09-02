// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The pages that use any of a set of components, each named once.
 *
 * <p>What matches is a node somewhere inside a page; what the caller wants is the page. So every
 * match is folded up to the nearest page above it, and a page holding forty matching nodes is one
 * entry with the distinct types listed once each. A caller planning a migration is deciding scope,
 * and an answer whose length was the node count rather than the page count would be useless for
 * exactly that.</p>
 *
 * <p>Folding upward is why the page is found by walking back up from the match rather than by
 * asking the match which page it belongs to: a component node knows its resource type and nothing
 * about pages, and the containing page is whichever page-typed ancestor is nearest.</p>
 */
public final class FindPagesUsingComponentsHandler implements CommandHandler {

    /** The name of the query this command issues, which the coverage policy declares. */
    public static final String QUERY_NAME = "find-pages-using-components";

    /** The property a node records its resource type in. */
    public static final String RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    /** The category a root nothing is at, or nobody may see, is refused under. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category a root the repository refused is reported under. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category a search that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public FindPagesUsingComponentsHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final FindPagesUsingComponentsCommand.Outcome asked =
                FindPagesUsingComponentsCommand.of(arguments, contract);
        if (asked instanceof final FindPagesUsingComponentsCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return searched(((FindPagesUsingComponentsCommand.Held) asked).command(), resolver,
                context);
    }

    private Answer searched(FindPagesUsingComponentsCommand command, ResourceResolver resolver,
                            CallerContext context) {
        final Resource root = resolver.getResource(command.rootPath());
        if (root == null) {
            return new Failed(ROOT_NOT_FOUND, command.rootPath() + " is not a path this caller can"
                    + " read, which is the same answer as nothing being there");
        }
        final Search search = new Search(command.resourceTypes(), context.discovery().limit());
        search.under(root);
        if (search.reachedTheBudget()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search reached the "
                    + context.discovery().limit() + " nodes it may examine and stopped; it is"
                    + " refused rather than shortened, because a partial list of pages using a"
                    + " component reads as the complete one and a migration would miss the rest");
        }
        return new Produced(PageListingResult.documentOf(
                pageOf(search.found(command.matchMode()), command.window(), contract), ""));
    }

    /**
     * One search of one subtree, folding every match up to the page that contains it.
     *
     * <p>The found pages are held in insertion order and keyed by path, which is what makes a page
     * with many matching nodes one entry: the second match on a page adds its type to the entry the
     * first one made rather than making a second entry.</p>
     */
    private static final class Search {

        private final List<String> wanted;
        private final long budget;
        private final SequencedMap<String, Matched> found = new LinkedHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong examined =
                new java.util.concurrent.atomic.AtomicLong();

        Search(List<String> wanted, long budget) {
            this.wanted = wanted;
            this.budget = budget;
        }

        void under(Resource resource) {
            if (reachedTheBudget()) {
                return;
            }
            examined.incrementAndGet();
            matchOn(resource);
            final Iterator<Resource> children = resource.listChildren();
            while (children.hasNext() && !reachedTheBudget()) {
                under(children.next());
            }
        }

        private void matchOn(Resource resource) {
            final String type = resource.getValueMap().get(RESOURCE_TYPE_PROPERTY, "");
            if (!wanted.contains(type)) {
                return;
            }
            containingPage(resource).ifPresent(page -> found.computeIfAbsent(page.getPath(),
                    path -> new Matched(ListChildPagesHandler.titleOf(page),
                            new LinkedHashSet<>())).types().add(type));
        }

        boolean reachedTheBudget() {
            return examined.get() >= budget;
        }

        /**
         * The pages this search answers, which is not every page it touched.
         *
         * <p>Under {@link MatchMode#ALL} a page that used four of the five types asked about is
         * not an answer to the question that was asked, and the types it did use are exactly what
         * says so. That is why the search records which types each page used rather than only that
         * it used one: the mode is applied to a fact the search gathered, not re-derived by
         * searching again once per type.</p>
         *
         * @param mode whether any of the types is enough or all of them are needed
         * @return the matching pages, each once, in the order the search reached them
         */
        List<PageListingResult.Page> found(MatchMode mode) {
            return found.entrySet().stream()
                    .filter(page -> mode == MatchMode.ANY
                            || page.getValue().types().containsAll(wanted))
                    .map(page -> new PageListingResult.Page(page.getKey(),
                            page.getValue().title()))
                    .toList();
        }

        /**
         * What one page matched with.
         *
         * @param title what the page is called, which is empty where it is called nothing
         * @param types the asked-about types this page actually used
         */
        private record Matched(String title, Set<String> types) {
        }
    }

    /**
     * The page one matching node belongs to, which is the nearest page-typed ancestor.
     *
     * <p>Walked upward rather than asked for: a component node knows its own resource type and
     * nothing about pages, and a node inside a page inside another page belongs to the inner one.
     * </p>
     *
     * @param match the node that matched
     * @return the page containing it, or nothing where the match is not inside a page at all
     */
    public static java.util.Optional<Resource> containingPage(Resource match) {
        Resource above = match;
        while (above != null) {
            if (ListChildPagesHandler.PAGE_TYPE.equals(String.valueOf(above.getValueMap()
                    .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
                return java.util.Optional.of(above);
            }
            above = above.getParent();
        }
        return java.util.Optional.empty();
    }

    /**
     * The window's worth of matches, taken out of the order the search found them in.
     *
     * @param found every matching page, each once
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the matches that page carries
     */
    public static List<PageListingResult.Page> pageOf(
            List<PageListingResult.Page> found, ResultWindow window, AgentContract contract) {
        final long offset = window instanceof final ResultWindow.Initial initial
                ? initial.offset() : 0;
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit() : contract.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        return found.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
