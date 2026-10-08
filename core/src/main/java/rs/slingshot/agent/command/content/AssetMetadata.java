// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import org.apache.sling.api.resource.Resource;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;

/** Exact optional asset metadata; binary length inspection never opens a binary stream. */
final class AssetMetadata {

    private static final String ORIGINAL = "jcr:content/renditions/original/jcr:content";
    private static final String DATA = "jcr:data";
    private static final String MIME_TYPE = "jcr:mimeType";

    private AssetMetadata() {
    }

    /**
     * Describes one asset using the current caller's readable properties.
     * @param asset the exact asset resource
     * @param contract the authenticated field bounds
     * @return detached typed metadata with explicit absence
     */
    static FindAssetsByMetadataResult.MatchedAsset describe(Resource asset, AgentContract contract) {
        final Optional<Resource> metadata = Optional.ofNullable(
                asset.getChild(FindAssetsByMetadataHandler.METADATA_NODE));
        final Optional<Resource> original = Optional.ofNullable(asset.getChild(ORIGINAL));
        final long formatBound = contract.value(ContractLimit.MAXIMUM_MEDIA_FORMAT_BYTES);
        final String format = metadata.flatMap(resource -> text(resource,
                FindAssetsByMetadataHandler.FORMAT_PROPERTY, formatBound))
                .or(() -> original.flatMap(resource -> text(resource, MIME_TYPE, formatBound)))
                .orElse(FindAssetsByMetadataResult.NO_FORMAT);
        return new FindAssetsByMetadataResult.MatchedAsset(asset.getPath(),
                original.map(AssetMetadata::length).orElse(FindAssetsByMetadataResult.NO_SIZE),
                format, metadata.map(resource -> tags(resource, contract)).orElseGet(List::of));
    }

    private static long length(Resource content) {
        return Optional.ofNullable(content.adaptTo(Node.class)).map(AssetMetadata::length)
                .orElseGet(() -> memoryLength(content));
    }

    private static long length(Node node) {
        try {
            if (!node.hasProperty(DATA)) {
                return FindAssetsByMetadataResult.NO_SIZE;
            }
            final var property = node.getProperty(DATA);
            if (property.isMultiple() || property.getType() != PropertyType.BINARY) {
                return FindAssetsByMetadataResult.NO_SIZE;
            }
            final long length = property.getLength();
            return length < 0 ? FindAssetsByMetadataResult.NO_SIZE : length;
        } catch (final RepositoryException unreadable) {
            return FindAssetsByMetadataResult.NO_SIZE;
        }
    }

    private static long memoryLength(Resource content) {
        // A non-JCR provider may expose an already resident byte array. Never request conversion
        // to bytes or an InputStream, which could download a repository binary to measure it.
        final Object stored = content.getValueMap().get(DATA);
        return stored instanceof final byte[] bytes ? bytes.length : FindAssetsByMetadataResult.NO_SIZE;
    }

    private static Optional<String> text(Resource resource, String property, long bound) {
        return Optional.ofNullable(resource.getValueMap().get(property))
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(value -> usable(value, bound));
    }

    private static boolean usable(String text, long bound) {
        return !text.isEmpty() && text.length() <= bound
                && text.getBytes(StandardCharsets.UTF_8).length <= bound
                && text.codePoints().noneMatch(value -> Character.isISOControl(value)
                        || value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE);
    }

    private static List<String> tags(Resource metadata, AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_ASSET_TAG_BYTES);
        return values(metadata).distinct().map(value -> Tag.of(value, bound))
                .sorted((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes()))
                .map(Tag::text).toList();
    }

    private static Stream<String> values(Resource metadata) {
        final Object stored = metadata.getValueMap().get(FindAssetsByMetadataHandler.TAGS_PROPERTY);
        if (stored instanceof final String text) {
            return Stream.of(text);
        }
        return stored instanceof final String[] texts ? Arrays.stream(texts) : Stream.empty();
    }

    /**
     * A validated tag and its one precomputed UTF-8 ordering key.
     *
     * @param text validated tag text
     * @param bytes UTF-8 bytes used for canonical ordering
     */
    private record Tag(String text, byte[] bytes) {
        static Tag of(String text, long bound) {
            if (text == null || !usable(text, bound)) {
                throw new DiscoveryRegistry.RowTooLarge();
            }
            return new Tag(text, text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
