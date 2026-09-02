// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The pages using one template, with when each last changed.
 *
 * <p>A root that is not there is refused; a root that is there and holds no matching page answers
 * an empty list. The difference is the whole point. Somebody planning a migration asks which pages
 * this change would affect: an empty answer to a mistyped root reads as "this change affects
 * nothing", they proceed, and the pages that actually use the template are the ones nobody looked
 * at. An empty answer must mean "there are none", and it can only mean that if a wrong question is
 * refused instead of answered.</p>
 */
public final class FindPagesByTemplateHandler implements CommandHandler {

    /** The name of the query this command issues, which the coverage policy declares. */
    public static final String QUERY_NAME = "find-pages-by-template";

    /** The property a page records its template in, under its content node. */
    public static final String TEMPLATE_PROPERTY = "cq:template";

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
    public FindPagesByTemplateHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final FindPagesByTemplateCommand.Outcome asked =
                FindPagesByTemplateCommand.of(arguments, contract);
        if (asked instanceof final FindPagesByTemplateCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return searched(((FindPagesByTemplateCommand.Held) asked).command(), resolver, context);
    }

    private Answer searched(FindPagesByTemplateCommand command, ResourceResolver resolver,
                            CallerContext context) {
        final Resource root = resolver.getResource(command.rootPath());
        if (root == null) {
            return new Failed(ROOT_NOT_FOUND, command.rootPath() + " is not a path this caller can"
                    + " read. It is refused rather than answered with an empty list, because an"
                    + " empty answer to a root nobody can read says this change affects nothing —"
                    + " and somebody planning a migration would believe it.");
        }
        final Search search = new Search(command.templatePath(), context.discovery().limit());
        search.under(root);
        if (search.reachedTheBudget()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search reached the "
                    + context.discovery().limit() + " nodes it may examine and stopped; it is"
                    + " refused rather than shortened, for the same reason an empty answer to a"
                    + " wrong root is refused");
        }
        return new Produced(PageListingResult.documentOf(
                pageOf(search.found(), command.window(), contract), ""));
    }

    /** One search of one subtree, carrying what it has examined and what it has found. */
    private static final class Search {

        private final String template;
        private final long budget;
        private final List<PageListingResult.Page> found = new ArrayList<>();
        private final java.util.concurrent.atomic.AtomicLong examined =
                new java.util.concurrent.atomic.AtomicLong();

        Search(String template, long budget) {
            this.template = template;
            this.budget = budget;
        }

        void under(Resource resource) {
            if (reachedTheBudget()) {
                return;
            }
            examined.incrementAndGet();
            matched(resource).ifPresent(found::add);
            final Iterator<Resource> children = resource.listChildren();
            while (children.hasNext() && !reachedTheBudget()) {
                under(children.next());
            }
        }

        boolean reachedTheBudget() {
            return examined.get() >= budget;
        }

        List<PageListingResult.Page> found() {
            return Collections.unmodifiableList(found);
        }

        private java.util.Optional<PageListingResult.Page> matched(
                Resource resource) {
            if (!ListChildPagesHandler.PAGE_TYPE.equals(typeOf(resource))) {
                return java.util.Optional.empty();
            }
            return java.util.Optional
                    .ofNullable(resource.getChild(ListChildPagesHandler.PAGE_CONTENT))
                    .filter(content -> template.equals(
                            content.getValueMap().get(TEMPLATE_PROPERTY, "")))
                    .map(content -> new PageListingResult.Page(resource.getPath(),
                            ListChildPagesHandler.titleOf(resource)));
        }
    }

    private static String typeOf(Resource resource) {
        return String.valueOf(resource.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class));
    }

    /**
     * The window's worth of matches, taken out of the order the search found them in.
     *
     * @param found every match
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the matches that page carries
     */
    public static List<PageListingResult.Page> pageOf(
            List<PageListingResult.Page> found, ResultWindow window,
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
