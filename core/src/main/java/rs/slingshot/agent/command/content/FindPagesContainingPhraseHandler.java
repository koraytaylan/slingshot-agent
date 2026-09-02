// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Pages whose text contains a phrase, under one subtree.
 *
 * <p>The budget is the whole safety story here, and it refuses rather than trims. A caller handed a
 * shortened list would read it as the complete answer — they asked which pages contain a phrase and
 * received a list of pages that contain it — and would conclude that every page not listed does not
 * match. That conclusion is wrong and nothing in the answer says so. Refusing is the only outcome
 * that leaves them knowing what they do not know.</p>
 *
 * <p>What comes back is an address and a title. No excerpt, no matched line, no surrounding
 * sentence: an excerpt is content, and answering with content would be performing a content read
 * that nobody checked this caller could make. A caller who wants to see the match asks for the page.
 * </p>
 */
public final class FindPagesContainingPhraseHandler implements CommandHandler {

    /** The name of the query this command issues, which the coverage policy declares. */
    public static final String QUERY_NAME = "find-pages-containing-phrase";

    /**
     * The properties a phrase is looked for in, which are the ones the page index covers.
     *
     * <p>Not every string a page carries. Adobe's own page index covers a page's title and its
     * description, and searching anything wider than what the index answers turns this command into
     * a walk of somebody's repository — which is the failure this whole plan exists to prevent. The
     * narrower search is the honest one: it is what an index-backed full-text search over pages can
     * actually be, and the coverage policy holds this list and the declared query to each other.</p>
     */
    public static final List<String> SEARCHED_PROPERTIES =
            List.of(ListChildPagesHandler.TITLE_PROPERTY, "jcr:description");

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
    public FindPagesContainingPhraseHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final FindPagesContainingPhraseCommand.Outcome asked =
                FindPagesContainingPhraseCommand.of(arguments, contract);
        if (asked instanceof final FindPagesContainingPhraseCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return searched(((FindPagesContainingPhraseCommand.Held) asked).command(), resolver,
                context);
    }

    private Answer searched(FindPagesContainingPhraseCommand command, ResourceResolver resolver,
                            CallerContext context) {
        final Resource root = resolver.getResource(command.rootPath());
        if (root == null) {
            return new Failed(ROOT_NOT_FOUND, command.rootPath() + " is not a path this caller can"
                    + " read, which is the same answer as nothing being there");
        }
        final Search search = new Search(command.phrase(), context.discovery().limit());
        search.under(root);
        if (search.reachedTheBudget()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search reached the "
                    + context.discovery().limit() + " nodes it may examine and stopped. It is"
                    + " refused rather than shortened: a list of matches that stopped early reads"
                    + " as the complete answer, and every page it did not reach would look like a"
                    + " page that does not match.");
        }
        return new Produced(PageListingResult.documentOf(
                pageOf(search.found(), command.window(), contract), ""));
    }

    /**
     * One search of one subtree, carrying what it has examined and what it has found.
     *
     * <p>The count is held here rather than passed down and back because every level has to see
     * the same one: a budget counted per branch is a budget a wide tree never reaches and a deep
     * one reaches at once, which is a bound meaning something different for every shape of site.
     * </p>
     */
    private static final class Search {

        private final String phrase;
        private final long budget;
        private final List<PageListingResult.Page> found = new ArrayList<>();
        private final java.util.concurrent.atomic.AtomicLong examined =
                new java.util.concurrent.atomic.AtomicLong();

        Search(String phrase, long budget) {
            this.phrase = phrase.toLowerCase(Locale.ROOT);
            this.budget = budget;
        }

        void under(Resource resource) {
            if (reachedTheBudget()) {
                return;
            }
            examined.incrementAndGet();
            if (ListChildPagesHandler.PAGE_TYPE.equals(typeOf(resource)) && contains(resource)) {
                found.add(new PageListingResult.Page(resource.getPath(), titleOf(resource)));
            }
            final Iterator<Resource> children = resource.listChildren();
            while (children.hasNext() && !reachedTheBudget()) {
                under(children.next());
            }
        }

        boolean reachedTheBudget() {
            return examined.get() >= budget;
        }

        List<PageListingResult.Page> found() {
            return java.util.Collections.unmodifiableList(found);
        }

        private boolean contains(Resource page) {
            // A page with no content node carries no text, so it matches nothing rather than
            // matching everything - which is what an empty stream would quietly do.
            return java.util.Optional
                    .ofNullable(page.getChild(ListChildPagesHandler.PAGE_CONTENT))
                    .map(content -> SEARCHED_PROPERTIES.stream()
                            .map(property -> content.getValueMap().get(property, ""))
                            .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(phrase)))
                    .orElse(false);
        }
    }

    private static String titleOf(Resource page) {
        final Resource content = page.getChild(ListChildPagesHandler.PAGE_CONTENT);
        return content == null ? ""
                : String.valueOf(content.getValueMap()
                        .get(ListChildPagesHandler.TITLE_PROPERTY, ""));
    }

    private static String typeOf(Resource resource) {
        return String.valueOf(resource.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class));
    }

    /**
     * The window's worth of matches, taken out of the order the search found them in.
     *
     * @param found every match, in the order the search returned them
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the matches that page carries
     */
    public static List<PageListingResult.Page> pageOf(List<PageListingResult.Page> found,
                                                      ResultWindow window,
                                                      AgentContract contract) {
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
