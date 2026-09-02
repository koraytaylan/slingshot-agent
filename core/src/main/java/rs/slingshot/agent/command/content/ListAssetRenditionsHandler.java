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
 * The renditions of one asset, described and never delivered.
 *
 * <p>It reads the asset's own renditions node directly. There is no query and nothing to index: the
 * question is about one asset, and its renditions are children of it.</p>
 *
 * <h2>Two different broken things</h2>
 *
 * <p>An address that is not an asset and an asset with no renditions node are told apart, because
 * they lead somewhere different. The first is somebody's mistake — they typed a folder, or a page —
 * and they fix it by typing something else. The second is a repository in a state the platform does
 * not produce, and somebody has to go and look at it. Reporting both as "not found" would send the
 * second person to check their spelling.</p>
 */
public final class ListAssetRenditionsHandler implements CommandHandler {

    /** Where an asset keeps its renditions. */
    public static final String RENDITIONS_NODE = "jcr:content/renditions";

    /** The property a rendition's size is recorded in, on its own content node. */
    public static final String SIZE_PROPERTY = "jcr:data_length";

    /** The property a rendition's media type is recorded in. */
    public static final String MEDIA_TYPE_PROPERTY = "jcr:mimeType";



    /** The category an asset nothing is at is refused under. */
    public static final String ASSET_NOT_FOUND = "asset_not_found";

    /** The category an asset the caller may not read is refused under. */
    public static final String ASSET_ACCESS_DENIED = "asset_access_denied";

    /** The category something that is there and is not a usable asset is refused under. */
    public static final String ASSET_INVALID = "asset_invalid";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public ListAssetRenditionsHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final ListAssetRenditionsCommand.Outcome asked =
                ListAssetRenditionsCommand.of(arguments, contract);
        if (asked instanceof final ListAssetRenditionsCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return listed(((ListAssetRenditionsCommand.Held) asked).command(), resolver, context);
    }

    private Answer listed(ListAssetRenditionsCommand command, ResourceResolver resolver,
                          CallerContext context) {
        final Resource asset = resolver.getResource(command.assetPath());
        if (asset == null) {
            return new Failed(ASSET_NOT_FOUND, command.assetPath() + " is not there");
        }
        if (!FindAssetsByMetadataHandler.ASSET_TYPE.equals(String.valueOf(asset.getValueMap()
                .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
            return new Failed(ASSET_INVALID, command.assetPath() + " is there and is not an asset, so"
                    + " it has no renditions; what is there is something else");
        }
        final Resource renditions = asset.getChild(RENDITIONS_NODE);
        if (renditions == null) {
            return new Failed(ASSET_INVALID, command.assetPath() + " is an asset with no renditions"
                    + " node at all. The platform does not produce an asset in that state, so this"
                    + " is a repository somebody has to look at rather than an address to retype.");
        }
        final List<ListAssetRenditionsResult.Rendition> held = renditionsOf(renditions,
                context.discovery().limit());
        if (held.size() > context.discovery().limit()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this asset holds more renditions than"
                    + " the " + context.discovery().limit() + " this caller may examine");
        }
        return new Produced(ListAssetRenditionsResult.documentOf(
                pageOf(held, command.window(), contract), ""));
    }

    private static List<ListAssetRenditionsResult.Rendition> renditionsOf(Resource renditions,
                                                                          long budget) {
        final List<ListAssetRenditionsResult.Rendition> held = new ArrayList<>();
        final Iterator<Resource> children = renditions.listChildren();
        while (children.hasNext() && held.size() <= budget) {
            held.add(describe(children.next()));
        }
        return Collections.unmodifiableList(held);
    }

    /**
     * What one rendition is, read from what the platform recorded about it.
     *
     * <p>Its own name, what kind of file it is, how large it is, and where it sits — which is
     * underneath the asset the caller named and therefore a path they could have written down
     * themselves.</p>
     *
     * @param rendition the rendition node
     * @return what a caller is told about it
     */
    public static ListAssetRenditionsResult.Rendition describe(Resource rendition) {
        final Resource content = rendition.getChild("jcr:content");
        final org.apache.sling.api.resource.ValueMap values =
                content == null ? rendition.getValueMap() : content.getValueMap();
        return new ListAssetRenditionsResult.Rendition(rendition.getName(),
                values.get(MEDIA_TYPE_PROPERTY, ""),
                values.get(SIZE_PROPERTY, 0L),
                rendition.getPath());
    }

    /**
     * The window's worth of renditions.
     *
     * @param held every rendition
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the renditions that page carries
     */
    public static List<ListAssetRenditionsResult.Rendition> pageOf(
            List<ListAssetRenditionsResult.Rendition> held, ResultWindow window,
            AgentContract contract) {
        final long offset = window instanceof final ResultWindow.Initial initial
                ? initial.offset() : 0;
        final long limit = window instanceof final ResultWindow.Initial initial
                ? initial.limit() : contract.value(ContractLimit.DEFAULT_RESULT_LIMIT);
        return held.stream().skip(offset).limit(limit).toList();
    }

    @Override
    public List<String> categories() {
        return List.of(ASSET_ACCESS_DENIED, ASSET_INVALID, ASSET_NOT_FOUND,
                DISCOVERY_BUDGET_EXCEEDED, "continuation_token_malformed",
                "continuation_token_integrity_invalid", "continuation_token_wrong_target",
                "continuation_token_wrong_query", "continuation_token_expired");
    }
}
