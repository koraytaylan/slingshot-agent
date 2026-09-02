// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.WorkflowInstanceState;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which workflow instances to find, and which page of them.
 *
 * <p>The states are required, and this is the one search in this plan where a required filter is
 * right. An author instance that has been running for a year holds hundreds of thousands of
 * completed instances and a handful of running ones, and every question anybody actually has is
 * about the handful. A search with no state would answer the year of history first, and the caller
 * would page through it looking for what they came for.</p>
 *
 * @param modelIdentifier which model, or {@link #EVERY_MODEL} for all of them
 * @param payloadPrefix what a payload path begins with, or {@link #EVERY_PAYLOAD}
 * @param states which states to include, which is never empty
 * @param window which page is wanted
 */
public record FindWorkflowInstancesCommand(String modelIdentifier, String payloadPrefix,
                                           List<WorkflowInstanceState> states,
                                           ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_workflow_instances";

    /** The member the model's identifier is carried in. */
    public static final String MODEL_IDENTIFIER = "model_identifier";

    /** The member the payload prefix is carried in. */
    public static final String PAYLOAD_PREFIX = "payload_prefix";

    /** The member the states to include are carried in. */
    public static final String STATES = "states";

    /** What the model says when the caller named none, which matches every model. */
    public static final String EVERY_MODEL = "";

    /** What the prefix says when the caller named none, which matches every payload. */
    public static final String EVERY_PAYLOAD = "";

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS =
            List.of(MODEL_IDENTIFIER, PAYLOAD_PREFIX, ResultWindow.ARGUMENT_MEMBER, STATES);

    /** The member a caller has to send, which is the states and only the states. */
    public static final List<String> REQUIRED = List.of(STATES);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** The states are absent, and a search with none would answer a year of history first. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The model's identifier is empty, or longer than one may be. */
        MODEL_REJECTED,
        /** The payload prefix is not an absolute repository path. */
        PAYLOAD_REJECTED,
        /** A named state is not one of the five there are, or there are too many. */
        STATE_REJECTED,
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(FindWorkflowInstancesCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** Holds a search whose states nothing can change afterwards. */
    public FindWorkflowInstancesCommand {
        states = List.copyOf(states);
    }

    /**
     * Which states this search includes.
     *
     * @return the states, which nothing may add to
     */
    @Override
    public List<WorkflowInstanceState> states() {
        return states;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds every member
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which instances and which page of them");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(STATES).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT, STATES + " is required. An instance that has"
                    + " been running for a year holds hundreds of thousands of completed workflows"
                    + " and a handful of running ones, and every question anybody has is about the"
                    + " handful — a search with no state would answer the history first.");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final Optional<List<WorkflowInstanceState>> states = statesIn(mapping, contract);
        if (states.isEmpty()) {
            return new Refused(Refusal.STATE_REJECTED, STATES + " is a list of the states to"
                    + " include, from " + WorkflowInstanceState.spellings() + ", within the "
                    + contract.value(ContractLimit.MAXIMUM_WORKFLOW_INSTANCE_STATES)
                    + " one search may name");
        }
        final long modelBound =
                contract.value(ContractLimit.MAXIMUM_WORKFLOW_MODEL_IDENTIFIER_BYTES);
        final Optional<DocumentValue> model = mapping.member(MODEL_IDENTIFIER);
        if (model.isPresent()
                && (!(model.orElseThrow() instanceof final DocumentValue.Text held)
                        || held.value().isEmpty() || held.value().length() > modelBound)) {
            return new Refused(Refusal.MODEL_REJECTED, MODEL_IDENTIFIER + " is what the platform"
                    + " calls one model: not empty, and within the " + modelBound
                    + " an identifier may be. Leave it out to search every model.");
        }
        return payloaded(mapping, contract, states.orElseThrow(), model
                .filter(DocumentValue.Text.class::isInstance)
                .map(held -> ((DocumentValue.Text) held).value())
                .orElse(EVERY_MODEL));
    }

    private static Outcome payloaded(DocumentValue.Mapping mapping, AgentContract contract,
                                     List<WorkflowInstanceState> states, String model) {
        final long pathBound = contract.value(ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES);
        final Optional<DocumentValue> asked = mapping.member(PAYLOAD_PREFIX);
        if (asked.isPresent()
                && (!(asked.orElseThrow() instanceof final DocumentValue.Text held)
                        || held.value().isEmpty() || held.value().charAt(0) != '/'
                        || held.value().length() > pathBound)) {
            return new Refused(Refusal.PAYLOAD_REJECTED, PAYLOAD_PREFIX + " is an absolute path"
                    + " beginning at the root. Leave it out to search every payload.");
        }
        final String prefix = asked
                .filter(DocumentValue.Text.class::isInstance)
                .map(held -> ((DocumentValue.Text) held).value())
                .orElse(EVERY_PAYLOAD);
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new FindWorkflowInstancesCommand(model, prefix, states,
                        ((ResultWindow.Held) window).window()));
    }

    private static Optional<List<WorkflowInstanceState>> statesIn(DocumentValue.Mapping mapping,
                                                                  AgentContract contract) {
        if (!(mapping.member(STATES).orElseThrow() instanceof final DocumentValue.Sequence items)
                || items.items().isEmpty()
                || items.items().size()
                        > contract.value(ContractLimit.MAXIMUM_WORKFLOW_INSTANCE_STATES)) {
            return Optional.empty();
        }
        final List<WorkflowInstanceState> states = items.items().stream()
                .filter(DocumentValue.Text.class::isInstance)
                .map(item -> WorkflowInstanceState.named(((DocumentValue.Text) item).value()))
                .flatMap(Optional::stream)
                .toList();
        return states.size() == items.items().size() ? Optional.of(states) : Optional.empty();
    }
}
