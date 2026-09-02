// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What an asset's renditions are, described rather than delivered.
 *
 * <p>A name, a media type, a size, and where it is. No bytes: an answer that carried the renditions
 * would be the very thing it is measuring, and an operator asking why storage has grown would be
 * handed the storage.</p>
 *
 * <p>The address is carried because the client's own schema requires it, and it discloses nothing:
 * a rendition lives underneath the asset the caller named, so its path is one the caller could
 * write down without being told. That is what makes it safe here and would not make an arbitrary
 * path safe elsewhere — the bound is that a caller learns only about the asset they asked about.
 * Fetching the bytes at that address remains a different command with a different bound.</p>
 */
public final class ListAssetRenditionsResult {

    private ListAssetRenditionsResult() {
    }

    /** The member the renditions are carried in. */
    public static final String MATCHES = "matches";

    /** The member one rendition's own name is carried in. */
    public static final String NAME = "name";

    /** The member one rendition's media type is carried in. */
    public static final String MEDIA_TYPE = "media_type";

    /** The member one rendition's size in bytes is carried in. */
    public static final String BYTE_LENGTH = "byte_length";

    /** The member one rendition's own address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(BYTE_LENGTH, MATCHES, MEDIA_TYPE, NAME,
            NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH);

    /**
     * The name the platform gives an asset's own original among its renditions.
     *
     * <p>It is listed rather than left out. An operator adding up what an asset costs needs the
     * original in the total, and a listing that silently omitted the largest thing would answer the
     * storage question wrongly by exactly the amount that matters most. Which rendition is the
     * original is read off its name rather than reported as a member of its own: the client's
     * schema carries the name, and the name is what says so.</p>
     */
    public static final String ORIGINAL_NAME = "original";

    /**
     * One rendition as a caller receives it.
     *
     * @param name the rendition's own name, which is how an operator refers to it
     * @param mediaType what kind of file it is
     * @param byteLength how large it is
     * @param repositoryPath where the rendition itself is
     */
    public record Rendition(String name, String mediaType, long byteLength,
                            String repositoryPath) {

        /**
         * Whether this rendition is the asset's own original.
         *
         * @return whether it is, which is a fact about the rendition rather than a caller's choice
         */
        public boolean isOriginal() {
            return ORIGINAL_NAME.equals(name);
        }
    }

    /**
     * The result one window of renditions produces.
     *
     * @param renditions the renditions
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<Rendition> renditions,
                                                   String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(renditions.stream()
                .map(ListAssetRenditionsResult::renditionOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue renditionOf(Rendition rendition) {
        final SequencedMap<String, DocumentValue> held = new LinkedHashMap<>();
        held.put(NAME, new DocumentValue.Text(rendition.name()));
        held.put(MEDIA_TYPE, new DocumentValue.Text(rendition.mediaType()));
        held.put(BYTE_LENGTH, new DocumentValue.Whole(rendition.byteLength()));
        held.put(REPOSITORY_PATH, new DocumentValue.Text(rendition.repositoryPath()));
        return new DocumentValue.Mapping(held);
    }
}
