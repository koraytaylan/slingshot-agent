// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.List;

/**
 * The categories the five asset commands report under.
 *
 * <p>Gathered here because the five share most of them and because each one's set is its registry
 * row's, read back by that command's own suite. A category spelled one way in a handler and another
 * in a row is a caller told about a failure the other half has never heard of.</p>
 */
public final class AssetHandlers {

    private AssetHandlers() {
    }

    /** The type an asset's own node has. */
    public static final String ASSET_TYPE = "dam:Asset";

    /** The type an asset folder's own node has. */
    public static final String FOLDER_TYPE = "sling:OrderedFolder";

    /** Where an asset keeps what is known about it. */
    public static final String METADATA_NODE = "jcr:content/metadata";

    /** Where an asset keeps its own bytes. */
    public static final String ORIGINAL_NODE = "jcr:content/renditions/original";

    /** The category a parent nothing is at is refused under. */
    public static final String PARENT_NOT_FOUND = "parent_not_found";

    /** The category a parent the caller may not write to is refused under. */
    public static final String PARENT_ACCESS_DENIED = "parent_access_denied";

    /** The category something already at the target address is refused under. */
    public static final String TARGET_ALREADY_EXISTS = "target_already_exists";

    /** The category a property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a payload this build will not store is refused under. */
    public static final String PAYLOAD_REJECTED = "payload_rejected";

    /** The category a payload larger than the contract allows is refused under. */
    public static final String PAYLOAD_TOO_LARGE = "payload_too_large";

    /** The category a media type this build does not store is refused under. */
    public static final String MEDIA_TYPE_UNSUPPORTED = "media_type_unsupported";

    /** The category an asset nothing is at is refused under. */
    public static final String ASSET_NOT_FOUND = "asset_not_found";

    /** The category an asset the caller may not change is refused under. */
    public static final String ASSET_ACCESS_DENIED = "asset_access_denied";

    /** The category something that is there and is not an asset is refused under. */
    public static final String ASSET_INVALID = "asset_invalid";

    /** The category an asset something points at is refused under, where the policy refuses. */
    public static final String ASSET_IS_REFERENCED = "asset_is_referenced";

    /** The category a property the repository will not let go of is refused under. */
    public static final String PROPERTY_NOT_REMOVABLE = "property_not_removable";

    /** The category a subtree larger than the contract allows is refused under. */
    public static final String DELETION_BUDGET_EXCEEDED = "deletion_budget_exceeded";

    /** The category a move with more references than may be adjusted is refused under. */
    public static final String ADJUSTMENT_BUDGET_EXCEEDED = "reference_adjustment_budget_exceeded";

    /** The category a source nothing is at is refused under. */
    public static final String SOURCE_NOT_FOUND = "source_not_found";

    /** The category a source the caller may not move is refused under. */
    public static final String SOURCE_ACCESS_DENIED = "source_access_denied";

    /** The category a destination whose parent is not there is refused under. */
    public static final String DESTINATION_PARENT_NOT_FOUND = "destination_parent_not_found";

    /** The category a destination something is already at is refused under. */
    public static final String DESTINATION_ALREADY_EXISTS = "destination_already_exists";

    /** The category a destination inside the source is refused under. */
    public static final String DESTINATION_INSIDE_SOURCE = "destination_inside_source";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    /**
     * Everything one folder creation can fail with.
     *
     * @return the categories
     */
    public static List<String> folderCategories() {
        return List.of(PARENT_NOT_FOUND, PARENT_ACCESS_DENIED, TARGET_ALREADY_EXISTS,
                PROPERTY_REJECTED, COMMIT_FAILED,
                rs.slingshot.agent.command.mutation.SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one asset creation can fail with.
     *
     * @return the categories
     */
    public static List<String> creationCategories() {
        return List.of(PARENT_NOT_FOUND, PARENT_ACCESS_DENIED, TARGET_ALREADY_EXISTS,
                PAYLOAD_REJECTED, PAYLOAD_TOO_LARGE, MEDIA_TYPE_UNSUPPORTED, COMMIT_FAILED,
                rs.slingshot.agent.command.mutation.SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one metadata change can fail with.
     *
     * @return the categories
     */
    public static List<String> metadataCategories() {
        return List.of(ASSET_NOT_FOUND, ASSET_ACCESS_DENIED, ASSET_INVALID, PROPERTY_REJECTED,
                PROPERTY_NOT_REMOVABLE, COMMIT_FAILED,
                rs.slingshot.agent.command.mutation.SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one removal can fail with.
     *
     * @return the categories
     */
    public static List<String> removalCategories() {
        return List.of(ASSET_NOT_FOUND, ASSET_ACCESS_DENIED, ASSET_INVALID, ASSET_IS_REFERENCED,
                DELETION_BUDGET_EXCEEDED, COMMIT_FAILED,
                rs.slingshot.agent.command.mutation.SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one move can fail with.
     *
     * @return the categories
     */
    public static List<String> moveCategories() {
        return List.of(SOURCE_NOT_FOUND, SOURCE_ACCESS_DENIED, DESTINATION_PARENT_NOT_FOUND,
                DESTINATION_ALREADY_EXISTS, DESTINATION_INSIDE_SOURCE, ADJUSTMENT_BUDGET_EXCEEDED,
                COMMIT_FAILED, rs.slingshot.agent.command.mutation.SingleCommit.OUTCOME_UNKNOWN);
    }
}
