// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Bounded live discovery under an exact readable root, including the root itself.
 *
 * <p>Version two uses provider depth-first order with explicit per-page progress. The runtime-owned
 * registry authenticates continuations before repository work, preserves the initial page limit,
 * rechecks current caller authority and releases the cursor on expiry or deactivation. A stable
 * subtree is visited once; concurrent changes are live rather than a snapshot.</p>
 */
public final class QueryPathsHandler implements CommandHandler {

    /** The category a root nothing is at, or nobody may see, is refused under. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category a root the repository refused is reported under. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category a search that ran out of its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;
    private final DiscoveryRegistry discovery;

    /**
     * Holds the authenticated bounds and the runtime-owned discovery registry.
     * @param contract the authenticated contract
     * @param discovery the cursor registry closed by the runtime
     */
    public QueryPathsHandler(AgentContract contract, DiscoveryRegistry discovery) {
        this.contract = contract;
        this.discovery = discovery;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final QueryPathsCommand.Outcome asked = QueryPathsCommand.of(arguments, contract);
        if (asked instanceof final QueryPathsCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final QueryPathsCommand command = ((QueryPathsCommand.Held) asked).command();
        return discovery.page(new DiscoveryRegistry.Request(QueryPathsCommand.WIRE_NAME,
                command.rootPath(), command.window(), arguments), resolver, context,
                new DiscoveryRegistry.Traversal(resource -> true,
                        resource -> matches(resource, command)
                                ? Optional.of(QueryPathsResult.matchOf(resource.getPath()))
                                : Optional.empty()));
    }

    private static boolean matches(Resource resource, QueryPathsCommand command) {
        final Resource current = java.util.Objects.requireNonNull(resource);
        if (!QueryPathsCommand.ANY_NODE_TYPE.equals(command.primaryNodeType())
                && !command.primaryNodeType().equals(typeOf(current))) {
            return false;
        }
        return command.predicates().stream()
                .allMatch(predicate -> predicate.isSatisfiedBy(
                        PredicatePropertyReader.at(current, predicate.propertyPath())));
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

    private static String typeOf(Resource resource) {
        return String.valueOf(resource.getValueMap().get("jcr:primaryType", String.class));
    }

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
