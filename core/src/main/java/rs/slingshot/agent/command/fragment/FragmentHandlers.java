// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.List;
import rs.slingshot.agent.command.mutation.SingleCommit;

/**
 * What a fragment is made of in a repository, and the categories the six commands report under.
 *
 * <p>Gathered here because the six share most of both. A category spelled one way in a handler and
 * another in a registry row is a caller told about a failure the other half has never heard of, and
 * a node name spelled one way here and another there is a fragment that reads back empty.</p>
 */
public final class FragmentHandlers {

    private FragmentHandlers() {
    }

    /** The type a content fragment's own node has, which is the type an asset has. */
    public static final String CONTENT_FRAGMENT_TYPE = "dam:Asset";

    /** The type a content fragment's content node has. */
    public static final String CONTENT_FRAGMENT_CONTENT_TYPE = "dam:AssetContent";

    /** The flag that tells a content fragment apart from every other asset. */
    public static final String CONTENT_FRAGMENT_FLAG = "contentFragment";

    /** The node a content fragment keeps its variations under, the master one included. */
    public static final String DATA_NODE = "data";

    /** The variation every content fragment has, which is the one it is created with. */
    public static final String MASTER_VARIATION = "master";

    /** The property a content fragment records its model in. */
    public static final String MODEL_PROPERTY = "cq:model";

    /** Where a content fragment model declares the elements a fragment made from it has. */
    public static final String MODEL_ELEMENTS = "jcr:content/model/cq:dialog/content/items";

    /** The type an experience fragment's own node has, which is the type a page has. */
    public static final String EXPERIENCE_FRAGMENT_TYPE = "cq:Page";

    /** The resource type an experience fragment's content node has. */
    public static final String EXPERIENCE_FRAGMENT_RESOURCE_TYPE =
            "cq/experience-fragments/components/xfpage";

    /** The type a node holding nothing but properties has. */
    public static final String UNSTRUCTURED_TYPE = "nt:unstructured";

    /** The category a parent nothing is at is refused under. */
    public static final String PARENT_NOT_FOUND = "parent_not_found";

    /** The category a parent the caller may not write to is refused under. */
    public static final String PARENT_ACCESS_DENIED = "parent_access_denied";

    /** The category something already at the target address is refused under. */
    public static final String TARGET_ALREADY_EXISTS = "target_already_exists";

    /** The category a model nothing is at is refused under. */
    public static final String MODEL_NOT_FOUND = "model_not_found";

    /** The category something that is there and is not a model is refused under. */
    public static final String MODEL_INVALID = "model_invalid";

    /** The category an element the model has never heard of is refused under. */
    public static final String ELEMENT_UNKNOWN = "element_unknown";

    /** The category an element value this contract will not write is refused under. */
    public static final String ELEMENT_VALUE_REJECTED = "element_value_rejected";

    /** The category a fragment nothing is at is refused under. */
    public static final String FRAGMENT_NOT_FOUND = "fragment_not_found";

    /** The category a fragment the caller may not reach is refused under. */
    public static final String FRAGMENT_ACCESS_DENIED = "fragment_access_denied";

    /** The category something that is there and is not a fragment is refused under. */
    public static final String FRAGMENT_INVALID = "fragment_invalid";

    /** The category a fragment something points at is refused under, where the policy refuses. */
    public static final String FRAGMENT_IS_REFERENCED = "fragment_is_referenced";

    /** The category a variation nothing is at is refused under. */
    public static final String VARIATION_NOT_FOUND = "variation_not_found";

    /** The category a variation the caller may not change is refused under. */
    public static final String VARIATION_ACCESS_DENIED = "variation_access_denied";

    /** The category something that is there and is not a variation is refused under. */
    public static final String VARIATION_INVALID = "variation_invalid";

    /** The category a template nothing is at is refused under. */
    public static final String TEMPLATE_NOT_FOUND = "template_not_found";

    /** The category something that is there and is not a template is refused under. */
    public static final String TEMPLATE_INVALID = "template_invalid";

    /** The category a property this contract will not write is refused under. */
    public static final String PROPERTY_REJECTED = "property_rejected";

    /** The category a property the repository will not let go of is refused under. */
    public static final String PROPERTY_NOT_REMOVABLE = "property_not_removable";

    /** The category a subtree larger than the contract allows is refused under. */
    public static final String DELETION_BUDGET_EXCEEDED = "deletion_budget_exceeded";

    /** The category a commit the repository refused is reported under. */
    public static final String COMMIT_FAILED = "repository_commit_failed";

    /**
     * Everything one content fragment creation can fail with.
     *
     * @return the categories
     */
    public static List<String> contentCreationCategories() {
        return List.of(PARENT_NOT_FOUND, PARENT_ACCESS_DENIED, TARGET_ALREADY_EXISTS,
                MODEL_NOT_FOUND, MODEL_INVALID, ELEMENT_UNKNOWN, ELEMENT_VALUE_REJECTED,
                COMMIT_FAILED, SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one content fragment change can fail with.
     *
     * @return the categories
     */
    public static List<String> contentUpdateCategories() {
        return List.of(FRAGMENT_NOT_FOUND, FRAGMENT_ACCESS_DENIED, FRAGMENT_INVALID,
                VARIATION_NOT_FOUND, ELEMENT_UNKNOWN, ELEMENT_VALUE_REJECTED, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one fragment removal can fail with, of either kind.
     *
     * <p>One list for both because the question is the same one: a fragment is either not there, not
     * a fragment, still pointed at, or larger than one delete removes. Two lists saying that twice
     * would be two places to forget to change.</p>
     *
     * @return the categories
     */
    public static List<String> removalCategories() {
        return List.of(FRAGMENT_NOT_FOUND, FRAGMENT_ACCESS_DENIED, FRAGMENT_INVALID,
                FRAGMENT_IS_REFERENCED, DELETION_BUDGET_EXCEEDED, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one experience fragment creation can fail with.
     *
     * @return the categories
     */
    public static List<String> experienceCreationCategories() {
        return List.of(PARENT_NOT_FOUND, PARENT_ACCESS_DENIED, TARGET_ALREADY_EXISTS,
                TEMPLATE_NOT_FOUND, TEMPLATE_INVALID, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }

    /**
     * Everything one experience fragment change can fail with.
     *
     * @return the categories
     */
    public static List<String> experienceUpdateCategories() {
        return List.of(VARIATION_NOT_FOUND, VARIATION_ACCESS_DENIED, VARIATION_INVALID,
                PROPERTY_REJECTED, PROPERTY_NOT_REMOVABLE, COMMIT_FAILED,
                SingleCommit.OUTCOME_UNKNOWN);
    }
}
