// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.ArrayList;
import java.util.List;
import rs.slingshot.agent.command.mutation.SingleCommit;

/**
 * The categories the six workflow commands report under.
 *
 * <p>Gathered here because the six share most of them, and because a category spelled one way in a
 * handler and another in a registry row is a caller told about a failure the other half has never
 * heard of.</p>
 */
public final class WorkflowHandlers {

    private WorkflowHandlers() {
    }

    /** The category a workflow engine this side could not ask is reported under. */
    public static final String INVENTORY_FAILED = "workflow_inventory_failed";

    /** The category a listing that reached its examination budget is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category a model nothing is at is refused under. */
    public static final String MODEL_NOT_FOUND = "model_not_found";

    /** The category something that is there and is not a runnable model is refused under. */
    public static final String MODEL_INVALID = "model_invalid";

    /** The category a payload nothing is at is refused under. */
    public static final String PAYLOAD_NOT_FOUND = "payload_not_found";

    /** The category a payload the caller may not change is refused under. */
    public static final String PAYLOAD_ACCESS_DENIED = "payload_access_denied";

    /** The category metadata this contract will not record is refused under. */
    public static final String METADATA_REJECTED = "metadata_rejected";

    /** The category an instance nothing is at is refused under. */
    public static final String INSTANCE_NOT_FOUND = "instance_not_found";

    /** The category an instance the caller may not reach is refused under. */
    public static final String INSTANCE_ACCESS_DENIED = "instance_access_denied";

    /** The category an instance that cannot be ended is refused under. */
    public static final String INSTANCE_NOT_TERMINABLE = "instance_not_terminable";

    /** The category an instance that cannot be held is refused under. */
    public static final String INSTANCE_NOT_SUSPENDABLE = "instance_not_suspendable";

    /** The category an answer larger than the contract allows is refused under. */
    public static final String RESULT_BUDGET_EXCEEDED = "result_budget_exceeded";

    /** The category the platform refusing a control is reported under. */
    public static final String CONTROL_REJECTED = "platform_control_rejected";

    /** The five ways a continuation token can be refused, which every paged command declares. */
    public static final List<String> CONTINUATION_CATEGORIES = List.of(
            "continuation_token_malformed", "continuation_token_integrity_invalid",
            "continuation_token_wrong_target", "continuation_token_wrong_query",
            "continuation_token_expired");

    /**
     * Everything one workflow listing can fail with, of either kind.
     *
     * @return the categories
     */
    public static List<String> listingCategories() {
        final List<String> categories = new ArrayList<>(List.of(DISCOVERY_BUDGET_EXCEEDED));
        categories.addAll(CONTINUATION_CATEGORIES);
        categories.add(INVENTORY_FAILED);
        return List.copyOf(categories);
    }

    /**
     * Everything one workflow start can fail with.
     *
     * @return the categories
     */
    public static List<String> startCategories() {
        return List.of(MODEL_NOT_FOUND, MODEL_INVALID, PAYLOAD_NOT_FOUND, PAYLOAD_ACCESS_DENIED,
                METADATA_REJECTED, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }

    /**
     * Everything one instance inspection can fail with.
     *
     * @return the categories
     */
    public static List<String> inspectionCategories() {
        return List.of(INSTANCE_NOT_FOUND, INSTANCE_ACCESS_DENIED, RESULT_BUDGET_EXCEEDED,
                INVENTORY_FAILED);
    }

    /**
     * Everything one instance control can fail with.
     *
     * @param refusal the category that control reports an instance it cannot act on under
     * @return the categories
     */
    public static List<String> controlCategories(String refusal) {
        return List.of(INSTANCE_NOT_FOUND, INSTANCE_ACCESS_DENIED, refusal, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }
}
