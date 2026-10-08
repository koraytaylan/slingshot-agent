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
 * The assets whose metadata matches, carrying back only what was asked about.
 *
 * <p>A runtime-owned cursor visits each readable folder and asset once in provider order. Asset
 * descendants are pruned; only the exact metadata and original rendition are inspected. Each
 * request reports bounded progress, including empty partial pages, and continuations resume
 * retained iterators under current caller authority.</p>
 */
public final class FindAssetsByMetadataHandler implements CommandHandler {

    /** The name of the query this command issues, which the coverage policy declares. */
    public static final String QUERY_NAME = "find-assets-by-metadata";

    /** The node type an asset has. */
    public static final String ASSET_TYPE = "dam:Asset";

    /** The category a root nothing is at, or nobody may see, is refused under. */
    public static final String ROOT_NOT_FOUND = "root_not_found";

    /** The category a root the repository refused is reported under. */
    public static final String ROOT_ACCESS_DENIED = "root_access_denied";

    /** The category a search that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category an argument this command does not take is refused under. */
    public static final String ARGUMENT_REJECTED = "argument_rejected";

    private final AgentContract contract;
    private final DiscoveryRegistry discovery;

    /**
     * Holds the authenticated bounds and runtime-owned cursor registry.
     *
     * @param contract the authenticated contract
     * @param discovery the shared registry closed during runtime deactivation
     */
    public FindAssetsByMetadataHandler(AgentContract contract, DiscoveryRegistry discovery) {
        this.contract = contract;
        this.discovery = discovery;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final FindAssetsByMetadataCommand.Outcome asked =
                FindAssetsByMetadataCommand.of(arguments, contract);
        if (asked instanceof final FindAssetsByMetadataCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        final var command = ((FindAssetsByMetadataCommand.Held) asked).command();
        final Search search = new Search(command);
        return discovery.page(new DiscoveryRegistry.Request(FindAssetsByMetadataCommand.WIRE_NAME,
                command.rootPath(), command.window(), arguments), resolver, context,
                new DiscoveryRegistry.Traversal(resource -> !Search.isAsset(resource),
                        resource -> search.matched(resource).map(FindAssetsByMetadataResult::assetOf)));
    }

    /** Current request criteria; no resolver, resources or caller metadata are retained. */
    private final class Search {

        private final FindAssetsByMetadataCommand command;

        Search(FindAssetsByMetadataCommand command) {
            this.command = command;
        }

        private static boolean isAsset(Resource resource) {
            return ASSET_TYPE.equals(String.valueOf(resource.getValueMap()
                    .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)));
        }

        /**
         * Whether one node is an asset this search is for, and what to say about it.
         *
         * <p>The narrowings are applied in the order they cost: what the node is, then the values
         * already read off it, then the predicates, which each resolve a property of their own.
         * A candidate ruled out by its type never has its metadata read at all.</p>
         *
         * @param resource the candidate
         * @return what a caller is told about it, or nothing where it is not a match
         */
        private Optional<FindAssetsByMetadataResult.MatchedAsset> matched(
                Resource resource) {
            if (!ASSET_TYPE.equals(String.valueOf(resource.getValueMap()
                    .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
                return Optional.empty();
            }
            final FindAssetsByMetadataResult.MatchedAsset asset = AssetMetadata.describe(resource, contract);
            if (!sized(asset) || !formatted(asset) || !tagged(asset)) {
                return Optional.empty();
            }
            return command.predicates().stream()
                    .allMatch(predicate -> predicate.isSatisfiedBy(
                            PredicatePropertyReader.at(resource, predicate.propertyPath())))
                    ? Optional.of(asset) : Optional.empty();
        }

        private boolean sized(FindAssetsByMetadataResult.MatchedAsset asset) {
            if (asset.byteLength() == FindAssetsByMetadataResult.NO_SIZE
                    && (command.minimumByteLength() != FindAssetsByMetadataCommand.NO_BOUND
                    || command.maximumByteLength() != FindAssetsByMetadataCommand.NO_BOUND)) {
                return false;
            }
            if (command.minimumByteLength() != FindAssetsByMetadataCommand.NO_BOUND
                    && asset.byteLength() < command.minimumByteLength()) {
                return false;
            }
            return command.maximumByteLength() == FindAssetsByMetadataCommand.NO_BOUND
                    || asset.byteLength() <= command.maximumByteLength();
        }

        private boolean formatted(FindAssetsByMetadataResult.MatchedAsset asset) {
            return command.mediaFormats().isEmpty()
                    || command.mediaFormats().contains(asset.mediaFormat());
        }

        private boolean tagged(FindAssetsByMetadataResult.MatchedAsset asset) {
            if (command.tags().isEmpty()) {
                return true;
            }
            return command.tagMatchMode() == MatchMode.ALL
                    ? asset.tags().containsAll(command.tags())
                    : command.tags().stream().anyMatch(asset.tags()::contains);
        }
    }

    /** Where an asset keeps what an operator searches it by. */
    public static final String METADATA_NODE = "jcr:content/metadata";

    /** The property an asset's format is recorded in. */
    public static final String FORMAT_PROPERTY = "dc:format";

    /** The property an asset's tags are recorded in. */
    public static final String TAGS_PROPERTY = "cq:tags";

    /** The optional recorded size property; discovery reads the original binary instead. */
    public static final String SIZE_PROPERTY = "dam:size";

    @Override
    public List<String> categories() {
        return List.of(ROOT_ACCESS_DENIED, ROOT_NOT_FOUND, DISCOVERY_BUDGET_EXCEEDED,
                "continuation_token_malformed", "continuation_token_integrity_invalid",
                "continuation_token_wrong_target", "continuation_token_wrong_query",
                "continuation_token_expired");
    }
}
