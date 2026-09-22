// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import rs.slingshot.agent.command.CallerContext;
import rs.slingshot.agent.command.CommandHandler;
import rs.slingshot.agent.command.PagingSupport;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The assets whose metadata matches, carrying back only what was asked about.
 *
 * <p>By the time this runs, the question is known to be answerable: a predicate no index covers was
 * refused when the argument was read, before an author instance spent anything. That ordering is
 * the point. A digital asset library is the largest thing in most repositories, and the property a
 * customer invented is exactly the one nobody indexed — discovering that half way through a walk
 * would mean the walk already happened.</p>
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

    /**
     * Holds one handler bound to the contract its bounds come from.
     *
     * @param contract the authenticated contract
     */
    public FindAssetsByMetadataHandler(AgentContract contract) {
        this.contract = contract;
    }

    @Override
    public Answer run(DocumentValue.Mapping arguments, ResourceResolver resolver,
                      CallerContext context) {
        final FindAssetsByMetadataCommand.Outcome asked =
                FindAssetsByMetadataCommand.of(arguments, contract);
        if (asked instanceof final FindAssetsByMetadataCommand.Refused refused) {
            return new Failed(ARGUMENT_REJECTED, refused.refusal() + ": " + refused.detail());
        }
        return searched(((FindAssetsByMetadataCommand.Held) asked).command(), arguments, resolver,
                context);
    }

    private Answer searched(FindAssetsByMetadataCommand command, DocumentValue.Mapping arguments,
                            ResourceResolver resolver, CallerContext context) {
        final Resource root = resolver.getResource(command.rootPath());
        if (root == null) {
            return new Failed(ROOT_NOT_FOUND, command.rootPath() + " is not a path this caller can"
                    + " read, which is the same answer as nothing being there");
        }
        final Search search = new Search(command);
        if (!search.under(root, context)) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search examined more than the "
                    + context.discovery().limit() + " nodes or ran longer than the "
                    + context.time().limit() + " milliseconds it is allowed before it had visited"
                    + " every asset under " + command.rootPath() + ", and stopped rather than"
                    + " answer with part of them; name a narrower folder instead");
        }
        final PagingSupport.Outcome<FindAssetsByMetadataResult.MatchedAsset> page =
                PagingSupport.page(search.found(), command.window(),
                        FindAssetsByMetadataCommand.WIRE_NAME, arguments, context,
                        contract);
        if (page instanceof final PagingSupport.Refused<FindAssetsByMetadataResult.MatchedAsset> refused) {
            return new Failed(refused.category(), refused.detail());
        }
        final PagingSupport.Page<FindAssetsByMetadataResult.MatchedAsset> accepted =
                ((PagingSupport.Accepted<FindAssetsByMetadataResult.MatchedAsset>) page).page();
        return new Produced(FindAssetsByMetadataResult.documentOf(accepted.rows(),
                accepted.continuationToken()));
    }

    /** One search of one subtree, carrying what it has examined and what it has found. */
    private static final class Search {

        private final FindAssetsByMetadataCommand command;
        private final List<FindAssetsByMetadataResult.MatchedAsset> found = new ArrayList<>();

        Search(FindAssetsByMetadataCommand command) {
            this.command = command;
        }

        /**
         * Examines every folder and asset under one root, inside the caller's budgets.
         *
         * <p>An asset is not opened: what lies below one is its renditions and its metadata, and
         * the metadata is read from the asset itself. Opening them would make a search of a
         * library a walk of every rendition in it.</p>
         *
         * @param root where the search starts
         * @param context the caller's budgets
         * @return whether every candidate was examined; false where a budget ran out first
         */
        boolean under(Resource root, CallerContext context) {
            return BoundedWalk.every(root, context, resource -> !isAsset(resource),
                    resource -> matched(resource).ifPresent(found::add));
        }

        private static boolean isAsset(Resource resource) {
            return ASSET_TYPE.equals(String.valueOf(resource.getValueMap()
                    .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)));
        }

        List<FindAssetsByMetadataResult.MatchedAsset> found() {
            return Collections.unmodifiableList(found);
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
        private java.util.Optional<FindAssetsByMetadataResult.MatchedAsset> matched(
                Resource resource) {
            if (!ASSET_TYPE.equals(String.valueOf(resource.getValueMap()
                    .get(ListChildPagesHandler.TYPE_PROPERTY, String.class)))) {
                return java.util.Optional.empty();
            }
            final Resource metadata = resource.getChild(METADATA_NODE);
            if (metadata == null) {
                return java.util.Optional.empty();
            }
            final FindAssetsByMetadataResult.MatchedAsset asset = describe(resource, metadata);
            if (!sized(asset) || !formatted(asset) || !tagged(asset)) {
                return java.util.Optional.empty();
            }
            return command.predicates().stream()
                    .allMatch(predicate -> predicate.isSatisfiedBy(
                            QueryPathsHandler.storedAt(resource, predicate.propertyPath())))
                    ? java.util.Optional.of(asset) : java.util.Optional.empty();
        }

        private boolean sized(FindAssetsByMetadataResult.MatchedAsset asset) {
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

    /** The property an asset's size is recorded in, on its original rendition. */
    public static final String SIZE_PROPERTY = "dam:size";

    /**
     * What one asset is, read from what the platform recorded about it.
     *
     * @param asset the asset node
     * @param metadata its metadata node
     * @return what a caller is told about it
     */
    public static FindAssetsByMetadataResult.MatchedAsset describe(Resource asset,
                                                                   Resource metadata) {
        final String[] tags = metadata.getValueMap().get(TAGS_PROPERTY, String[].class);
        return new FindAssetsByMetadataResult.MatchedAsset(asset.getPath(),
                metadata.getValueMap().get(SIZE_PROPERTY,
                        FindAssetsByMetadataResult.NO_SIZE),
                metadata.getValueMap().get(FORMAT_PROPERTY,
                        FindAssetsByMetadataResult.NO_FORMAT),
                tags == null ? List.of() : List.of(tags));
    }

    /**
     * The window's worth of matches, taken out of the order the search found them in.
     *
     * @param found every match
     * @param window which page is wanted
     * @param contract the authenticated contract, which declares the default page size
     * @return the matches that page carries
     */
    public static List<FindAssetsByMetadataResult.MatchedAsset> pageOf(
            List<FindAssetsByMetadataResult.MatchedAsset> found, ResultWindow window,
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
