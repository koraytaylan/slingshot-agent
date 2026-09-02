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
        return searched(((FindAssetsByMetadataCommand.Held) asked).command(), resolver, context);
    }

    private Answer searched(FindAssetsByMetadataCommand command, ResourceResolver resolver,
                            CallerContext context) {
        final Resource root = resolver.getResource(command.rootPath());
        if (root == null) {
            return new Failed(ROOT_NOT_FOUND, command.rootPath() + " is not a path this caller can"
                    + " read, which is the same answer as nothing being there");
        }
        final Search search = new Search(command, context.discovery().limit());
        search.under(root);
        if (search.reachedTheBudget()) {
            return new Failed(DISCOVERY_BUDGET_EXCEEDED, "this search reached the "
                    + context.discovery().limit() + " nodes it may examine and stopped; it is"
                    + " refused rather than shortened, because a partial list of assets reads as"
                    + " the complete one");
        }
        return new Produced(FindAssetsByMetadataResult.documentOf(
                pageOf(search.found(), command.window(), contract), ""));
    }

    /** One search of one subtree, carrying what it has examined and what it has found. */
    private static final class Search {

        private final FindAssetsByMetadataCommand command;
        private final long budget;
        private final List<FindAssetsByMetadataResult.MatchedAsset> found = new ArrayList<>();
        private final java.util.concurrent.atomic.AtomicLong examined =
                new java.util.concurrent.atomic.AtomicLong();

        Search(FindAssetsByMetadataCommand command, long budget) {
            this.command = command;
            this.budget = budget;
        }

        void under(Resource resource) {
            if (reachedTheBudget()) {
                return;
            }
            examined.incrementAndGet();
            matched(resource).ifPresent(found::add);
            final Iterator<Resource> children = resource.listChildren();
            while (children.hasNext() && !reachedTheBudget()) {
                under(children.next());
            }
        }

        boolean reachedTheBudget() {
            return examined.get() >= budget;
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
