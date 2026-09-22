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
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The children of one anchor that match a listing, read directly rather than searched for.
 *
 * <p>It issues no query at all, which is why it appears in no row of the declared-query inventory
 * and needs no index on anybody's deployment. Listing what is directly under a known node is
 * something the repository answers by looking, and turning it into a search would make the most
 * common operation an operator performs depend on an index somebody has to maintain.</p>
 *
 * <p>The anchor is any readable node, not only a page: a site root, a language folder, and a page
 * all hold children a caller asks about, and the client's own contract names the anchor an anchor
 * rather than a page. What stays filtered is the match: a child is listed only when it is an
 * immediate child and, for a typed listing, exactly the type the request named.</p>
 *
 * <p>Order is the client's own: strictly ascending by repository-path bytes, which is what makes a
 * continuation token mean one page after another.</p>
 */
public final class ChildListingHandler implements CommandHandler {

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

    /** The empty spelling, which stands for a child no filter admits rather than a type. */
    private static final String EVERY_TYPE = "";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public ChildListingHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ChildListingArgument.Outcome read = ChildListingArgument.read(arguments, contract,
                memberNames(arguments), requiredNames(arguments));
        if (read instanceof final ChildListingArgument.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final ChildListingArgument.Accepted accepted =
                ((ChildListingArgument.Read) read).accepted();
        return listed(typeOf(arguments), accepted, arguments, resolver, context);
    }

    /**
     * The members the argument may carry, which the typed listing widens by one.
     *
     * @param arguments the argument document
     * @return every member this listing takes
     */
    private static List<String> memberNames(DocumentValue arguments) {
        return typed(arguments) ? ListChildNodesByTypeCommand.MEMBERS
                : ListChildNodesCommand.MEMBERS;
    }

    /**
     * The members the argument must carry, which the typed listing widens by one.
     *
     * @param arguments the argument document
     * @return every member this listing requires
     */
    private static List<String> requiredNames(DocumentValue arguments) {
        return typed(arguments) ? ListChildNodesByTypeCommand.REQUIRED
                : ListChildNodesCommand.REQUIRED;
    }

    private static boolean typed(DocumentValue arguments) {
        return arguments instanceof final DocumentValue.Mapping mapping
                && mapping.member(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE).isPresent();
    }

    /**
     * The primary type this listing admits, or the empty spelling for every child.
     *
     * @param arguments the argument document
     * @return the type, or empty for every child
     */
    private static String typeOf(DocumentValue arguments) {
        if (!typed(arguments)) {
            return EVERY_TYPE;
        }
        return ChildListingArgument.primaryNodeType(arguments);
    }

    private Answer listed(String type, ChildListingArgument.Accepted accepted,
                          DocumentValue.Mapping arguments, ResourceResolver resolver,
                          CallerContext context) {
        final Resource parent = resolver.getResource(accepted.rootPath());
        if (parent == null) {
            return new Failed(ROOT_NOT_FOUND, whyNothingIsListed(accepted.rootPath()));
        }
        final List<ChildNodeListingResult.Child> children = matching(parent, type);
        final PagingSupport.Outcome<ChildNodeListingResult.Child> page = PagingSupport.page(
                children, accepted.window(), wireName(type), arguments, context,
                contract);
        if (page instanceof final PagingSupport.Refused<ChildNodeListingResult.Child> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<ChildNodeListingResult.Child> acceptedPage =
                ((PagingSupport.Accepted<ChildNodeListingResult.Child>) page).page();
        return new Produced(ChildNodeListingResult.documentOf(acceptedPage.rows(),
                acceptedPage.continuationToken()));
    }

    private static String wireName(String type) {
        return EVERY_TYPE.equals(type) ? ListChildNodesCommand.WIRE_NAME
                : ListChildNodesByTypeCommand.WIRE_NAME;
    }

    /**
     * The children of one anchor that match `type`, in ascending path order.
     *
     * <p>The one place a child listing decides what matches, so the page listing is a typed
     * listing with {@code cq:Page} rather than a second implementation of the same filter. The
     * empty spelling admits every child, which is the untyped listing.</p>
     *
     * @param parent the anchor
     * @param type the exact primary type a child must have, or empty for every child
     * @return the matching children, which is every child of that type
     */
    static List<ChildNodeListingResult.Child> matching(Resource parent, String type) {
        final List<ChildNodeListingResult.Child> children = new ArrayList<>();
        final Iterator<Resource> held = parent.listChildren();
        while (held.hasNext()) {
            final Resource child = held.next();
            final String childType = typeOf(child);
            if (EVERY_TYPE.equals(type) || type.equals(childType)) {
                children.add(new ChildNodeListingResult.Child(child.getPath(), childType,
                        ListChildPagesHandler.titleOf(child)));
            }
        }
        return ChildNodeListingResult.ascending(children);
    }

    /**
     * What a caller is told when there is nothing to list.
     *
     * @param parent the path that was asked for
     * @return the sentence a caller receives
     */
    public static String whyNothingIsListed(String parent) {
        return parent + " is not a path this caller can read, which is the same answer as nothing"
                + " being there";
    }

    /**
     * The primary type of one resource, which every node carries.
     *
     * @param resource the node
     * @return its primary type
     */
    static String typeOf(Resource resource) {
        return String.valueOf(resource.getValueMap().get(TYPE_PROPERTY, String.class));
    }

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
