// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Bounded live discovery of pages containing one exact contiguous phrase.
 *
 * <p>Only title and description are searched, without case folding or normalization. A row
 * carries a page address and an optional title, never an excerpt. The runtime-owned cursor
 * reports explicit completeness and may return an empty partial page. Its next request resumes
 * retained provider iterators under current caller authority rather than scanning a prefix again.</p>
 */
public final class FindPagesContainingPhraseHandler implements CommandHandler {

    /** The only page-content properties this command searches. */
    public static final List<String> SEARCHED_PROPERTIES =
            List.of(ListChildPagesHandler.TITLE_PROPERTY, "jcr:description");

    /** The category of a missing or initially unreadable root. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category of revoked root authority. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category of a cursor capacity or retained traversal bound refusal. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category of malformed command arguments. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;
    private final DiscoveryRegistry discovery;

    /**
     * Creates a phrase search using the runtime's shared bounded cursor owner.
     * @param contract the authenticated command limits
     * @param discovery the runtime's shared cursor owner
     */
    public FindPagesContainingPhraseHandler(AgentContract contract, DiscoveryRegistry discovery) {
        this.contract = contract;
        this.discovery = discovery;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver, CallerContext context) {
        final FindPagesContainingPhraseCommand.Outcome asked =
                FindPagesContainingPhraseCommand.of(arguments, contract);
        if (asked instanceof final FindPagesContainingPhraseCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final var command = ((FindPagesContainingPhraseCommand.Held) asked).command();
        return discovery.page(new DiscoveryRegistry.Request(FindPagesContainingPhraseCommand.WIRE_NAME,
                command.rootPath(), command.window(), arguments), resolver, context,
                PhraseSearchTraversal.of(command.rootPath(), command.phrase()));
    }

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
