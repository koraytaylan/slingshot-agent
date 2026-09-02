// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
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
 * The children of one page, read directly rather than searched for.
 *
 * <p>It issues no query. Not "a cheap query" — none at all, which is why this command appears in no
 * row of the declared-query inventory and needs no index on anybody's deployment. Listing what is
 * directly under a known node is something the repository answers by looking, and turning it into a
 * search would make the most common operation an operator performs depend on an index somebody has
 * to maintain.</p>
 *
 * <p>Order is the repository's own. A caller navigating a site sees what an author sees in their
 * own console; sorting here would answer a different question whose answer looks plausible and is
 * not the site.</p>
 */
public final class ListChildPagesHandler implements CommandHandler {

    /** The node type a page has, which is what makes a child a page rather than a folder. */
    public static final String PAGE_TYPE = "cq:Page";

    /** Where a page keeps its own properties, including the title a listing shows. */
    public static final String PAGE_CONTENT = "jcr:content";

    /** The property a page's title is kept in. */
    public static final String TITLE_PROPERTY = "jcr:title";

    /** The property every node's type is kept in. */
    public static final String TYPE_PROPERTY = "jcr:primaryType";

    /** The category a parent nothing is at, or nobody may see, is refused under. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category a parent the repository refused is reported under. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category a listing that ran out of its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * <p>The contract is the only thing it holds, and nothing about any one run: what a run may use
     * arrives as an argument to {@link #run}.</p>
     *
     * @param contract the authenticated contract
     */
    public ListChildPagesHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ListChildPagesCommand.Outcome asked =
                ListChildPagesCommand.of(arguments, contract);
        if (asked instanceof final ListChildPagesCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return listed(((ListChildPagesCommand.Held) asked).command(), resolver, context);
    }

    private Answer listed(ListChildPagesCommand command, ResourceResolver resolver,
                          CallerContext context) {
        final Resource parent = resolver.getResource(command.rootPath());
        if (parent == null) {
            return new Failed(ROOT_NOT_FOUND, whyNothingIsListed(Absence.NOTHING_IS_THERE,
                    command.rootPath()));
        }
        if (!PAGE_TYPE.equals(typeOf(parent))) {
            return new Failed(ROOT_NOT_FOUND, whyNothingIsListed(Absence.IT_IS_NOT_A_PAGE,
                    command.rootPath()));
        }
        final List<PageListingResult.Page> children = childrenOf(parent);
        if (children.size() > context.discovery().limit()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this parent holds more than the "
                    + context.discovery().limit() + " children this caller may examine");
        }
        return new Produced(PageListingResult.documentOf(
                pageOf(children, command.window(), contract), ""));
    }

    /** Why a listing has no parent to list, which decides what the caller does next. */
    public enum Absence {
        /** There is nothing at that path, or nothing this caller may see. */
        NOTHING_IS_THERE,
        /** Something is there and it is not a page, so it has no child pages to list. */
        IT_IS_NOT_A_PAGE
    }

    /**
     * What a caller is told when there are no children to list.
     *
     * <p>Both are the same category, because the client's own closed set has one spelling for a
     * root that cannot anchor a listing. They are not the same sentence: a caller whose path is
     * simply wrong retypes it, and a caller who pointed at a folder goes looking for the page
     * inside it. The category tells them it failed; this tells them what to do.</p>
     *
     * @param absence why nothing is listed
     * @param parent the path that was asked for
     * @return the sentence a caller receives
     */
    public static String whyNothingIsListed(Absence absence, String parent) {
        return switch (absence) {
            case NOTHING_IS_THERE -> parent + " is not a path this caller can read, which is the"
                    + " same answer as nothing being there";
            case IT_IS_NOT_A_PAGE -> parent + " is not a page, so it has no child pages; a page is"
                    + " what this command lists the children of";
        };
    }

    private static List<PageListingResult.Page> childrenOf(Resource parent) {
        final List<PageListingResult.Page> children = new ArrayList<>();
        final Iterator<Resource> held = parent.listChildren();
        while (held.hasNext()) {
            final Resource child = held.next();
            if (PAGE_TYPE.equals(typeOf(child))) {
                children.add(new PageListingResult.Page(child.getPath(), titleOf(child)));
            }
        }
        return List.copyOf(children);
    }

    /**
     * What one page is called, which is empty where it is called nothing.
     *
     * <p>Public because four commands answer the same page listing and a page's title is read the
     * same way for all four. A second reading of it would be a second answer to "what is this page
     * called" on the day one of them started looking somewhere else.</p>
     *
     * @param page the page
     * @return its title, or empty where it has none
     */
    public static String titleOf(Resource page) {
        final Resource content = page.getChild(PAGE_CONTENT);
        return content == null ? ""
                : String.valueOf(content.getValueMap().get(TITLE_PROPERTY, ""));
    }

    private static String typeOf(Resource resource) {
        return String.valueOf(resource.getValueMap().get(TYPE_PROPERTY, String.class));
    }

    /**
     * The window's worth of children, taken out of the repository's own order.
     *
     * <p>Kept apart from the reading so that paging can be proved without a repository, and taken
     * by position out of one order rather than by re-listing, so two pages never overlap or skip.
     * </p>
     *
     * @param children every child, in repository order
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the children that page carries
     */
    public static List<PageListingResult.Page> pageOf(
            List<PageListingResult.Page> children, ResultWindow window,
            AgentContract contract) {
        final long offset = window instanceof final ResultWindow.Initial initial
                ? initial.offset() : 0;
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit() : contract.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        return children.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
