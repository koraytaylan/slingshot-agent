// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.wire;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every refusal this build knows how to report, and what each one means.
 *
 * <p>The set is closed and a code outside it is refused rather than passed through: a code a caller
 * has never seen is a code they will handle by matching on the message beside it, which is the
 * situation stable codes exist to prevent.</p>
 *
 * <p>Each code carries one sentence, and that sentence is the whole message. Nothing a caller sent
 * is interpolated into it — not a path, not a name, not a value — because a message assembled from
 * a submission is a way to have somebody else's bytes read back out of a log.</p>
 */
public enum ErrorCode {

    /** Nobody authenticated the request, and this agent answers nobody in particular nothing. */
    UNAUTHENTICATED("unauthenticated",
            "This request was not authenticated. Authenticate as a user of this deployment and"
                    + " submit it again."),

    /** Somebody authenticated and is not in a group this deployment permits to submit. */
    FORBIDDEN("forbidden",
            "This caller is not a member of a group this deployment permits to submit work. Ask an"
                    + " operator to widen the permitted groups, or submit as a caller who is."),

    /** Nothing this agent serves answers that address. */
    UNKNOWN_ROUTE("unknown_route",
            "This agent serves no route at that address. Read the capability document to find the"
                    + " routes this build serves."),

    /** The route exists and does not answer that method. */
    METHOD_NOT_PERMITTED("method_not_permitted",
            "This route does not answer that method. Read the capability document for the method"
                    + " each route answers."),

    /** The bytes are not a document under the bounds the contract declares. */
    DOCUMENT_REFUSED("document_refused",
            "The bytes sent are not a document this agent can read under its declared bounds. Send"
                    + " a document within the bounds the capability document publishes."),

    /** The document means contracts this build does not. */
    CONTRACT_MISMATCH("contract_mismatch",
            "This document names contracts this build does not speak. Read the capability document"
                    + " for the contracts this build means, and submit under those."),

    /** The five-field identity is not complete or not in shape. */
    IDENTITY_INCOMPLETE("identity_incomplete",
            "The command contract identity sent is not complete. Send all five fields of the"
                    + " identity the command registry publishes for this command."),

    /** The idempotency key sent is not the one derived here for this submission. */
    IDEMPOTENCY_KEY_MISMATCH("idempotency_key_mismatch",
            "The idempotency key sent is not the one this submission derives. Derive the key from"
                    + " the submission rather than sending one chosen elsewhere."),

    /** The command ran to the budget the contract declares and was stopped. */
    EXECUTION_BUDGET_SPENT("execution_budget_spent",
            "This command reached the execution budget this deployment declares and was stopped."
                    + " Submit work that fits inside that budget, or split it."),

    /** The store or a caller's share of it is full. */
    CAPACITY_EXHAUSTED("capacity_exhausted",
            "This agent has no capacity left for more work of this kind. Wait for outstanding work"
                    + " to finish, or ask an operator about the declared capacity."),

    /** Something here failed, and the caller can do nothing about it except try later. */
    INTERNAL_REFUSAL("internal_refusal",
            "This agent refused the request for a reason inside itself. Try again later, and ask an"
                    + " operator to read the agent's own logs for this operation.");

    private final String spelling;
    private final String sentence;

    ErrorCode(String spelling, String sentence) {
        this.spelling = spelling;
        this.sentence = sentence;
    }

    /**
     * How this code is spelled on the wire.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The whole message this code carries, which is the same every time.
     *
     * @return the sentence
     */
    public String sentence() {
        return sentence;
    }

    /**
     * The code one spelling names.
     *
     * @param spelling the spelling
     * @return the code, or nothing where this build knows no such code
     */
    public static Optional<ErrorCode> named(String spelling) {
        return Arrays.stream(values())
                .filter(code -> code.spelling.equals(spelling))
                .findFirst();
    }

    /**
     * Every spelling this build knows, in the order a committed schema lists them.
     *
     * @return the spellings, sorted
     */
    public static List<String> spellings() {
        return Arrays.stream(values())
                .map(ErrorCode::spelling)
                .sorted()
                .toList();
    }
}
