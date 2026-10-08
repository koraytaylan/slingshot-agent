// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagedQuery;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.continuation.ContinuationKeyAuthority;
import rs.slingshot.agent.continuation.ContinuationToken;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The pages among the immediate children of one anchor, read directly rather than searched for.
 *
 * <p>It issues no query. Not "a cheap query" — none at all, which is why this command appears in no
 * row of the declared-query inventory and needs no index on anybody's deployment. Listing what is
 * directly under a known node is something the repository answers by looking, and turning it into a
 * search would make the most common operation an operator performs depend on an index somebody has
 * to maintain.</p>
 *
 * <p>The anchor is any node this caller can read, not only a page. A site root, a language folder,
 * and a page all hold pages somewhere directly beneath them, and the client's own contract names
 * the anchor an anchor rather than a page: it says the command reports the pages that are immediate
 * children of one anchor. Requiring the anchor itself to be a page would refuse the most ordinary
 * question an operator asks — what is under this root — and answer it with a category whose closed
 * vocabulary has one spelling for "this anchor cannot be listed".</p>
 *
 * <p>What a match is does not change: a child is listed only when it is exactly a page. A folder
 * among the children is not one, and a grandchild is not a child. Those children are the ones
 * {@code list_child_nodes_by_type} returns for {@code cq:Page}. This handler builds that request,
 * runs it, and keeps the path and title. It does not walk the anchor itself.</p>
 *
 * <p>The typed listing orders paths by their bytes, as the client's canonical result contract
 * requires. Every page retains that order.</p>
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

    /** The handler the runtime registers for {@code list_child_nodes_by_type}. */
    private final CommandHandler nodesByType;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * <p>The contract is the only thing it holds about a run, and the typed listing is the handler
     * the runtime registers for {@code list_child_nodes_by_type}. What a run may use arrives as an
     * argument to {@link #run}.</p>
     *
     * @param contract the authenticated contract
     */
    public ListChildPagesHandler(AgentContract contract) {
        this(contract, new ChildListingHandler(contract));
    }

    /**
     * Holds the contract and the typed listing this command runs.
     *
     * @param contract the authenticated contract
     * @param nodesByType the handler that executes {@code list_child_nodes_by_type}
     */
    ListChildPagesHandler(AgentContract contract, CommandHandler nodesByType) {
        this.contract = contract;
        this.nodesByType = nodesByType;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ListChildPagesCommand.Outcome asked =
                ListChildPagesCommand.of(arguments, contract);
        if (asked instanceof final ListChildPagesCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return listed(((ListChildPagesCommand.Held) asked).command(), arguments, resolver, context);
    }

    private Answer listed(ListChildPagesCommand command, DocumentValue.Mapping arguments,
                          ResourceResolver resolver, CallerContext context) {
        final PagingSupport.Preparation prepared = PagingSupport.prepare(command.window(),
                ListChildPagesCommand.WIRE_NAME, arguments, context, contract);
        if (prepared instanceof final PagingSupport.WindowRefused refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Ready ready = (PagingSupport.Ready) prepared;
        final Answer typed = nodesByType.run(typedRequest(arguments, command, ready), resolver,
                context);
        return typed instanceof final Produced produced
                ? producedPages(produced.result(), ready, context) : typed;
    }

    /**
     * The typed request: the same anchor and result window, with the type fixed to {@code cq:Page}.
     *
     * <p>A continuation cannot be handed across. Its token names this command, and the typed listing
     * would refuse it as someone else's query. The resumed position is asked for as an initial
     * window of the same offset and the original authenticated limit, which is the page the token
     * named.</p>
     *
     * @param arguments the page listing's own argument
     * @param command the page listing those arguments named
     * @param ready the authenticated original window and fixed expiry
     * @return the typed listing's argument
     */
    private DocumentValue.Mapping typedRequest(DocumentValue.Mapping arguments,
                                               ListChildPagesCommand command,
                                               PagingSupport.Ready ready) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE,
                new DocumentValue.Text(PAGE_TYPE));
        members.put(ListChildPagesCommand.ROOT_PATH,
                arguments.member(ListChildPagesCommand.ROOT_PATH).orElseThrow());
        if (!(command.window() instanceof ResultWindow.Continuation)
                && arguments.member(ResultWindow.ARGUMENT_MEMBER).isPresent()) {
            members.put(ResultWindow.ARGUMENT_MEMBER,
                    arguments.member(ResultWindow.ARGUMENT_MEMBER).orElseThrow());
        } else {
            members.put(ResultWindow.ARGUMENT_MEMBER, initialWindow(ready.offset(), ready.limit()));
        }
        return new DocumentValue.Mapping(members);
    }

    private static DocumentValue initialWindow(long offset, long limit) {
        final SequencedMap<String, DocumentValue> members = new LinkedHashMap<>();
        members.put(ResultWindow.MODE, new DocumentValue.Text(ResultWindow.INITIAL_MODE));
        members.put(ResultWindow.OFFSET, new DocumentValue.Whole(offset));
        members.put(ResultWindow.LIMIT, new DocumentValue.Whole(limit));
        return new DocumentValue.Mapping(members);
    }

    private Answer producedPages(DocumentValue.Mapping typed, PagingSupport.Ready prepared,
                                 CallerContext context) {
        final DocumentValue.Mapping pages = project(typed, prepared, context);
        if (typed.member(ChildNodeListingResult.NEXT_CONTINUATION_TOKEN).isPresent()
                && pages.member(PageListingResult.NEXT_CONTINUATION_TOKEN).isEmpty()) {
            return new Failed("continuation_token_integrity_invalid",
                    "continuation authority is unavailable");
        }
        return new Produced(pages);
    }

    /**
     * The page document one typed result projects to: path and title, with an empty title omitted.
     *
     * <p>The typed listing's successor token belongs to {@code list_child_nodes_by_type}. The token
     * returned here is issued for this command at the same next position, so a caller resumes a
     * page listing rather than a different query that happens to share an anchor.</p>
     *
     * @param typed the typed listing's result
     * @param prepared the authenticated original window and fixed expiry
     * @param context the caller's continuation authority
     * @return the page listing result
     */
    private DocumentValue.Mapping project(DocumentValue.Mapping typed,
                                          PagingSupport.Ready prepared,
                                          CallerContext context) {
        final List<PageListingResult.Page> pages = new ArrayList<>();
        final DocumentValue matches = typed.member(ChildNodeListingResult.MATCHES).orElseThrow();
        for (final DocumentValue item : ((DocumentValue.Sequence) matches).items()) {
            pages.add(asPage((DocumentValue.Mapping) item));
        }
        final String token = typed.member(ChildNodeListingResult.NEXT_CONTINUATION_TOKEN).isEmpty()
                ? "" : successor(prepared.offset() + pages.size(), prepared, context);
        return PageListingResult.documentOf(pages, token);
    }

    private static PageListingResult.Page asPage(DocumentValue.Mapping node) {
        final String path = ((DocumentValue.Text) node.member(
                ChildNodeListingResult.REPOSITORY_PATH).orElseThrow()).value();
        final String title = node.member(ChildNodeListingResult.TITLE)
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse("");
        return new PageListingResult.Page(path, title);
    }

    private String successor(long nextOffset, PagingSupport.Ready prepared, CallerContext context) {
        if (!(context.paging() instanceof final CallerContext.Available paging)
                || !(paging.authority().read() instanceof final ContinuationKeyAuthority.Read read)) {
            return "";
        }
        final PagedQuery query = new PagedQuery(ListChildPagesCommand.WIRE_NAME,
                paging.targetDigest(), paging.generation());
        return query.tokenFor(new PagedQuery.Page<>(List.of(), new PagedQuery.More(nextOffset)),
                        prepared.digest(), read.ring(), prepared.limit(),
                        prepared.expiresAtUnixMilliseconds())
                .map(ContinuationToken::rendered)
                .orElse("");
    }

    /**
     * What a caller is told when there is nothing to list.
     *
     * <p>The category is the same one every rooted search reports for an anchor it cannot resolve,
     * because the client's own closed set has one spelling for a root that cannot anchor a listing.
     * The sentence is what tells the caller which of the two they did: a path that is not there and
     * a path this caller may not read are the same answer, because telling them apart would tell a
     * caller which nodes exist that they may not see.</p>
     *
     * @param parent the path that was asked for
     * @return the sentence a caller receives
     */
    public static String whyNothingIsListed(String parent) {
        return parent + " is not a path this caller can read, which is the same answer as nothing"
                + " being there";
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
