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
import rs.slingshot.agent.json.DocumentValue;

/**
 * Addresses under one root, of one type, a page at a time.
 *
 * <p>It issues the one query this command declares — a subtree and a primary type, filtering on
 * nothing else — because that is the shape Oak's own node-type index answers on every supported
 * deployment. A query that filtered on anything else would be answered by walking the repository,
 * and walking somebody's author instance is the failure this whole plan exists to prevent.</p>
 *
 * <p>Ordering is by path, ascending. It has to be <em>some</em> total order that two pages agree
 * on, and path is the only property this command is allowed to look at — which makes it the only
 * candidate. An unordered query would let two pages overlap or skip, and a caller reading both
 * would see one address twice and another never, with nothing to tell them it happened.</p>
 */
public final class QueryPathsHandler implements CommandHandler {

    /** The name of the query this command issues, which the coverage policy declares. */
    public static final String QUERY_NAME = "query-paths-by-type";

    /** The category a root nothing is at, or nobody may see, is refused under. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category a root the repository refused is reported under. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category a search that ran out of its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * <p>The contract is the only thing it holds, and it holds nothing about any one run: what a
     * run may use arrives as an argument to {@link #run}, so two callers running this command at
     * once cannot reach anything of each other's.</p>
     *
     * @param contract the authenticated contract
     */
    public QueryPathsHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final QueryPathsCommand.Outcome asked = QueryPathsCommand.of(arguments, contract);
        if (asked instanceof final QueryPathsCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return searched(((QueryPathsCommand.Held) asked).command(), resolver, context);
    }

    private Answer searched(QueryPathsCommand command, ResourceResolver resolver,
                            CallerContext context) {
        final Resource root = resolver.getResource(command.rootPath());
        if (root == null) {
            return new Failed(ROOT_NOT_FOUND, command.rootPath() + " is not a path this caller can"
                    + " read, which is the same answer as nothing being there");
        }
        final Gathered gathered = gather(root, command, context.discovery().limit());
        if (gathered.ending() == Ending.THE_BUDGET_RAN_OUT) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search examined more than the "
                    + context.discovery().limit() + " nodes it is allowed, and stopped rather than"
                    + " going on");
        }
        return new Produced(QueryPathsResult.documentOf(gathered.paths(), ""));
    }

    /**
     * What one search found, and whether it was allowed to finish.
     *
     * @param paths the addresses, in ascending path order
     * @param ending whether the search reached the end of the subtree or ran out of examinations
     */
    private record Gathered(List<String> paths, Ending ending) {
    }

    /** How a search stopped. */
    private enum Ending {
        /** It reached the end of the subtree, so what it found is everything there is. */
        NOTHING_LEFT_TO_EXAMINE,
        /** It ran out of examinations, so what it found is not an answer to anything. */
        THE_BUDGET_RAN_OUT
    }

    /**
     * Whether one candidate is one of the addresses this search is for.
     *
     * <p>The type narrows what the query returns; the predicates are applied to those rows here.
     * That is the split the index requires: a query can be answered from an index because it asks
     * about a node's type and its path, and a predicate about an arbitrary property is a filter
     * over rows already found rather than a second index nobody has. Which is why the predicates
     * are bounded by the examination budget and the query is not.</p>
     *
     * @param resource the candidate
     * @param command what was asked
     * @return whether it belongs in the answer
     */
    private static boolean matches(Resource resource, QueryPathsCommand command) {
        if (!QueryPathsCommand.ANY_NODE_TYPE.equals(command.primaryNodeType())
                && !command.primaryNodeType().equals(typeOf(resource))) {
            return false;
        }
        return command.predicates().stream()
                .allMatch(predicate -> predicate.isSatisfiedBy(
                        storedAt(resource, predicate.propertyPath())));
    }

    /**
     * What the repository holds under one relative property path, rendered as text.
     *
     * <p>Resolved exactly: the path names child resources and then one property, with no descendant
     * search and no name aliasing. A predicate that finds nothing has found nothing, rather than
     * finding something similarly named somewhere below.</p>
     *
     * @param candidate the node being examined
     * @param propertyPath the property, relative to it
     * @return its values, and empty where the property is not there
     */
    public static List<String> storedAt(Resource candidate, String propertyPath) {
        final int lastSlash = propertyPath.lastIndexOf('/');
        final Resource holding = lastSlash < 0 ? candidate
                : candidate.getChild(propertyPath.substring(0, lastSlash));
        if (holding == null) {
            return List.of();
        }
        final String name = propertyPath.substring(lastSlash + 1);
        final String[] several = holding.getValueMap().get(name, String[].class);
        if (several != null) {
            return List.of(several);
        }
        final String one = holding.getValueMap().get(name, String.class);
        return one == null ? List.of() : List.of(one);
    }

    private static Gathered gather(Resource root, QueryPathsCommand command, long budget) {
        final List<String> found = new ArrayList<>();
        final java.util.Deque<Resource> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        long examined = 0;
        while (!pending.isEmpty()) {
            final Resource resource = pending.removeFirst();
            examined = examined + 1;
            if (examined > budget) {
                return new Gathered(List.of(), Ending.THE_BUDGET_RAN_OUT);
            }
            if (matches(resource, command)) {
                found.add(resource.getPath());
            }
            final Iterator<Resource> children = resource.listChildren();
            while (children.hasNext()) {
                pending.addLast(children.next());
            }
        }
        return new Gathered(found.stream().sorted().toList(),
                Ending.NOTHING_LEFT_TO_EXAMINE);
    }

    private static String typeOf(Resource resource) {
        return String.valueOf(resource.getValueMap().get("jcr:primaryType", String.class));
    }

    /**
     * The page one window takes out of everything the search found.
     *
     * <p>Kept apart from the searching so that paging can be proved without a repository. Two pages
     * of one enumeration never overlap and never skip, because both are taken from one ascending
     * order by position rather than by re-running a query whose answer may have moved.</p>
     *
     * @param found everything the search found, in ascending order
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the addresses that page carries
     */
    public static List<String> pageOf(List<String> found, ResultWindow window,
                                      AgentContract contract) {
        final long offset = window instanceof final ResultWindow.Initial initial
                ? initial.offset() : 0;
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit()
                : contract.value(rs.slingshot.agent.contract.ContractLimit.DEFAULT_RESULT_LIMIT);
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
