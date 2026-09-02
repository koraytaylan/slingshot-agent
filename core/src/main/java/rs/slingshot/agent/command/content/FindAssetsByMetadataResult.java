// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The assets one search found, each with what an operator decides about it from.
 *
 * <p>An address, how large it is, what kind of file it is, and what it is tagged with. Not its
 * whole metadata: a search result carrying every property of every match is a content read nobody
 * checked the caller could make, and it is also the answer that no longer fits in one page.</p>
 *
 * <p>Everything but the address may be absent, because an asset the platform has not finished
 * processing has no recorded size or format yet — and saying nothing is the truthful answer where
 * a zero would read as an empty file.</p>
 */
public final class FindAssetsByMetadataResult {

    private FindAssetsByMetadataResult() {
    }

    /** The member the matching assets are carried in. */
    public static final String MATCHES = "matches";

    /** The member one asset's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member one asset's size is carried in, where the platform recorded one. */
    public static final String BYTE_LENGTH = "byte_length";

    /** The member one asset's format is carried in, where the platform recorded one. */
    public static final String MEDIA_FORMAT = "media_format";

    /** The member one asset's tags are carried in. */
    public static final String TAGS = "tags";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(BYTE_LENGTH, MATCHES, MEDIA_FORMAT,
            NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH, TAGS);

    /** Where the platform has recorded no size for an asset, which is not a size of zero. */
    public static final long NO_SIZE = -1;

    /** Where the platform has recorded no format, which is not a format that is empty. */
    public static final String NO_FORMAT = "";

    /**
     * One matching asset as a caller receives it.
     *
     * @param repositoryPath where the asset is
     * @param byteLength how large it is, or {@link #NO_SIZE} where the platform recorded none
     * @param mediaFormat what kind of file it is, or {@link #NO_FORMAT} where none was recorded
     * @param tags what it is tagged with, which is empty where it is tagged with nothing
     */
    public record MatchedAsset(String repositoryPath, long byteLength, String mediaFormat,
                               List<String> tags) {

        /** Holds the tags apart from whatever produced them. */
        public MatchedAsset {
            tags = List.copyOf(tags);
        }

        /**
         * What this asset is tagged with.
         *
         * @return the tags, which nothing may add to
         */
        @Override
        public List<String> tags() {
            return Collections.unmodifiableList(tags);
        }
    }

    /**
     * The result one window of matching assets produces.
     *
     * @param matched the assets, in the order the search found them
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<MatchedAsset> matched,
                                                   String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(matched.stream()
                .map(FindAssetsByMetadataResult::assetOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue assetOf(MatchedAsset asset) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(REPOSITORY_PATH, new DocumentValue.Text(asset.repositoryPath()));
        if (asset.byteLength() != NO_SIZE) {
            held.put(BYTE_LENGTH, new DocumentValue.Whole(asset.byteLength()));
        }
        if (!NO_FORMAT.equals(asset.mediaFormat())) {
            held.put(MEDIA_FORMAT, new DocumentValue.Text(asset.mediaFormat()));
        }
        if (!asset.tags().isEmpty()) {
            held.put(TAGS, new DocumentValue.Sequence(asset.tags().stream()
                    .map(tag -> (DocumentValue) new DocumentValue.Text(tag))
                    .toList()));
        }
        return new DocumentValue.Mapping(held);
    }
}
