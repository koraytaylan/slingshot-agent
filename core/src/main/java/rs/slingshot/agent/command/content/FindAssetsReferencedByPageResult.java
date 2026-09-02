// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The assets one page references, each once, with every place each was found.
 *
 * <p>An asset referenced from four components on one page is one asset. What a caller does with
 * this answer is decide whether the asset can be removed, and that decision is about the asset
 * rather than about how many times it appears — but every place it appears is listed, because the
 * caller who decides it cannot be removed then has to go and look at those places.</p>
 *
 * <p>A place is a property path relative to the page, and it is the whole of what is said about a
 * reference. How a reference was written — a property holding the path, a path inside a fragment of
 * markup — is this side's business while it looks and not a distinction the client asked to be told
 * about: what an author needs is which property to edit.</p>
 */
public final class FindAssetsReferencedByPageResult {

    private FindAssetsReferencedByPageResult() {
    }

    /** The member the referenced assets are carried in. */
    public static final String MATCHES = "matches";

    /** The member one asset's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the places one asset was referenced from are carried in. */
    public static final String REFERENCE_PATHS = "reference_paths";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS =
            List.of(MATCHES, NEXT_CONTINUATION_TOKEN, REFERENCE_PATHS, REPOSITORY_PATH);

    /**
     * One referenced asset as a caller receives it.
     *
     * @param repositoryPath where the asset is
     * @param referencePaths every distinct place on the page it was referenced from
     */
    public record ReferencedAsset(String repositoryPath, List<String> referencePaths) {

        /** Holds the places apart from whatever produced them. */
        public ReferencedAsset {
            referencePaths = List.copyOf(referencePaths);
        }

        /**
         * Every distinct place this asset was referenced from.
         *
         * @return the places, which nothing may add to
         */
        @Override
        public List<String> referencePaths() {
            return Collections.unmodifiableList(referencePaths);
        }
    }

    /**
     * The result one window of referenced assets produces.
     *
     * @param referenced the referenced assets, each appearing once
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<ReferencedAsset> referenced,
                                                   String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(referenced.stream()
                .map(FindAssetsReferencedByPageResult::assetOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue assetOf(ReferencedAsset referenced) {
        final SequencedMap<String, DocumentValue> asset = new LinkedHashMap<>();
        asset.put(REPOSITORY_PATH, new DocumentValue.Text(referenced.repositoryPath()));
        asset.put(REFERENCE_PATHS, new DocumentValue.Sequence(referenced.referencePaths().stream()
                .map(place -> (DocumentValue) new DocumentValue.Text(place))
                .toList()));
        return new DocumentValue.Mapping(asset);
    }
}
