// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Bounded live discovery of nearest pages using any or all requested component types.
 *
 * <p>Each open page retains at most one witness per requested type. Child pages own their own
 * witnesses; a parent never inherits them. A page is finalized once its content is consumed,
 * after any child pages, and current witness visibility and ancestry are rechecked before emission
 * or replay. An anchor inside a page may return that containing page. Results describe partial
 * progress in provider traversal order, without complete-tree collection or global sorting.</p>
 */
public final class FindPagesUsingComponentsHandler implements CommandHandler {

    /** The exact property compared against requested component resource types. */
    public static final String RESOURCE_TYPE_PROPERTY = "sling:resourceType";

    /** The category of a missing or initially unreadable root. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category of revoked root authority. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category of a cursor capacity, proof or retained traversal bound refusal. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category of malformed command arguments. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;
    private final DiscoveryRegistry discovery;

    /**
     * Creates a component search using the runtime's shared bounded cursor owner.
     * @param contract authenticated command limits
     * @param discovery the runtime's shared cursor owner
     */
    public FindPagesUsingComponentsHandler(AgentContract contract, DiscoveryRegistry discovery) {
        this.contract = contract;
        this.discovery = discovery;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver, CallerContext context) {
        final FindPagesUsingComponentsCommand.Outcome asked =
                FindPagesUsingComponentsCommand.of(arguments, contract);
        if (asked instanceof final FindPagesUsingComponentsCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final var command = ((FindPagesUsingComponentsCommand.Held) asked).command();
        return discovery.page(new DiscoveryRegistry.Request(FindPagesUsingComponentsCommand.WIRE_NAME,
                command.rootPath(), command.window(), arguments), resolver, context,
                new DiscoveryRegistry.Traversal(resource -> true,
                        FindPagesUsingComponentsHandler::describePage,
                        command.resourceTypes(), command.matchMode()));
    }

    private static Optional<DocumentValue.Mapping> describePage(Resource resource) {
        if (!ListChildPagesHandler.PAGE_TYPE.equals(ChildListingHandler.typeOf(resource))) {
            return Optional.empty();
        }
        final SequencedMap<String, DocumentValue> values = new LinkedHashMap<>();
        values.put("repository_path", new DocumentValue.Text(resource.getPath()));
        final String title = ListChildPagesHandler.titleOf(resource);
        if (!title.isEmpty()) {
            values.put("title", new DocumentValue.Text(title));
        }
        return Optional.of(new DocumentValue.Mapping(values));
    }

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
