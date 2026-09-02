// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.ArrayList;
import java.util.List;
import rs.slingshot.agent.command.mutation.SingleCommit;

/**
 * The categories the four configuration commands report under.
 *
 * <p>Gathered here because the four share most of them, and because a category spelled one way in a
 * handler and another in a registry row is a caller told about a failure the other half has never
 * heard of.</p>
 */
public final class ConfigurationHandlers {

    private ConfigurationHandlers() {
    }

    /** The category a configuration the platform could not be asked about is reported under. */
    public static final String LOOKUP_FAILED = "configuration_lookup_failed";

    /** The category a search that would examine more than it may is refused under. */
    public static final String LOOKUP_BUDGET_EXCEEDED = "configuration_lookup_budget_exceeded";

    /** The category an identifier naming something other than what was expected is refused under. */
    public static final String LOOKUP_MISMATCH = "configuration_lookup_mismatch";

    /** The category an identifier naming more than one configuration is refused under. */
    public static final String LOOKUP_AMBIGUOUS = "configuration_lookup_ambiguous";

    /** The category a search that would examine more resources than allowed is refused under. */
    public static final String DISCOVERY_BUDGET_EXCEEDED = "discovery_budget_exceeded";

    /** The category the platform refusing a control is reported under. */
    public static final String CONTROL_REJECTED = "platform_control_rejected";

    /** The category a value this contract will not read or write is refused under. */
    public static final String VALUE_MALFORMED = "configuration_value_malformed";

    /** The category a type or cardinality this build does not carry is refused under. */
    public static final String VALUE_UNSUPPORTED = "configuration_value_unsupported";

    /** The category a value larger than the contract allows is refused under. */
    public static final String VALUE_BUDGET_EXCEEDED = "configuration_value_budget_exceeded";

    /** The category an answer larger than the contract allows is refused under. */
    public static final String RESULT_BUDGET_EXCEEDED = "configuration_result_budget_exceeded";

    /**
     * The five ways a continuation token can be refused, which every paged command declares.
     *
     * <p>Spelled here rather than derived, because they are the client's own spellings and the
     * client is the half that reads them.</p>
     */
    public static final List<String> CONTINUATION_CATEGORIES = List.of(
            "continuation_token_malformed", "continuation_token_integrity_invalid",
            "continuation_token_wrong_target", "continuation_token_wrong_query",
            "continuation_token_expired");

    /**
     * Everything one configuration search can fail with.
     *
     * @return the categories
     */
    public static List<String> searchCategories() {
        final List<String> categories = new ArrayList<>(List.of(DISCOVERY_BUDGET_EXCEEDED));
        categories.addAll(CONTINUATION_CATEGORIES);
        categories.add(LOOKUP_FAILED);
        categories.add(LOOKUP_BUDGET_EXCEEDED);
        return List.copyOf(categories);
    }

    /**
     * Everything one configuration inspection can fail with.
     *
     * @return the categories
     */
    public static List<String> inspectionCategories() {
        return List.of(LOOKUP_FAILED, LOOKUP_MISMATCH, LOOKUP_AMBIGUOUS, LOOKUP_BUDGET_EXCEEDED,
                VALUE_MALFORMED, VALUE_UNSUPPORTED, VALUE_BUDGET_EXCEEDED, RESULT_BUDGET_EXCEEDED);
    }

    /**
     * Everything one configuration change can fail with.
     *
     * @return the categories
     */
    public static List<String> updateCategories() {
        return List.of(LOOKUP_FAILED, LOOKUP_MISMATCH, LOOKUP_AMBIGUOUS, VALUE_MALFORMED,
                VALUE_UNSUPPORTED, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }

    /**
     * Everything one configuration removal can fail with.
     *
     * @return the categories
     */
    public static List<String> removalCategories() {
        return List.of(LOOKUP_FAILED, LOOKUP_MISMATCH, LOOKUP_AMBIGUOUS, CONTROL_REJECTED,
                SingleCommit.PLATFORM_CONTROL_OUTCOME_UNKNOWN);
    }
}
