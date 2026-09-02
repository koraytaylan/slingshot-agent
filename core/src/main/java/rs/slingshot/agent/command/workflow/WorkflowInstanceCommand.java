// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.platform.SuspensionState;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One workflow instance, named, and for one of the three commands also what to do to it.
 *
 * <p>Shared by the command that reads an instance, the one that ends it, and the one that holds it,
 * because the identifier is read identically by all three and the third simply carries one member
 * more. Reading it once is what keeps the bound the same on all three; three copies is three
 * chances for one of them to accept an identifier the others refuse.</p>
 *
 * @param instanceIdentifier what the platform calls the instance
 */
public record WorkflowInstanceCommand(String instanceIdentifier) {

    /** The wire name of the command that reads one instance. */
    public static final String INSPECT_WIRE_NAME = "inspect_workflow_instance";

    /** The wire name of the command that ends one. */
    public static final String TERMINATE_WIRE_NAME = "terminate_workflow_instance";

    /** The wire name of the command that holds one or lets it go. */
    public static final String SUSPEND_WIRE_NAME = "set_workflow_instance_suspension";

    /** The member the instance's identifier is carried in. */
    public static final String INSTANCE_IDENTIFIER = "instance_identifier";

    /** The member the requested state is carried in, on the one command that asks for one. */
    public static final String REQUESTED_STATE = "requested_state";

    /** Every member the two identifier-only commands take. */
    public static final List<String> MEMBERS = List.of(INSTANCE_IDENTIFIER);

    /** Every member the command that asks for a state takes. */
    public static final List<String> SUSPENSION_MEMBERS =
            List.of(INSTANCE_IDENTIFIER, REQUESTED_STATE);

    /** Why an argument is not one these commands take. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The identifier is empty, or longer than one may be. */
        IDENTIFIER_REJECTED,
        /** The requested state is not one of the two anybody may ask for. */
        STATE_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument these commands take.
     *
     * @param command what was asked
     */
    public record Held(WorkflowInstanceCommand command) implements Outcome {
    }

    /**
     * One they do not.
     *
     * @param refusal why they do not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads an argument naming one instance and nothing else.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        return read(arguments, contract, MEMBERS);
    }

    /** What reading a suspension argument produced: the request, or the one reason there is none. */
    public sealed interface SuspensionOutcome permits Suspension, SuspensionRefused {
    }

    /**
     * A request to hold one instance or let it go.
     *
     * @param instanceIdentifier what the platform calls the instance
     * @param requested which of the two states to put it in
     */
    public record Suspension(String instanceIdentifier, SuspensionState requested)
            implements SuspensionOutcome {
    }

    /**
     * One this command does not take.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record SuspensionRefused(Refusal refusal, String detail) implements SuspensionOutcome {
    }

    /**
     * Reads an argument naming one instance and which of the two states to put it in.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the identifier
     * @return the request, or the one reason there is none
     */
    public static SuspensionOutcome suspension(DocumentValue arguments, AgentContract contract) {
        final Outcome read = read(arguments, contract, SUSPENSION_MEMBERS);
        if (read instanceof final Refused refused) {
            return new SuspensionRefused(refused.refusal(), refused.detail());
        }
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new SuspensionRefused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming one workflow instance");
        }
        if (mapping.member(REQUESTED_STATE).isEmpty()) {
            return new SuspensionRefused(Refusal.MEMBER_ABSENT, REQUESTED_STATE + " is required;"
                    + " holding an instance and letting one go are not something this side may"
                    + " choose between");
        }
        return SuspensionState.of(mapping.member(REQUESTED_STATE).orElseThrow())
                .<SuspensionOutcome>map(state -> new Suspension(
                        ((Held) read).command().instanceIdentifier(), state))
                .orElseGet(() -> new SuspensionRefused(Refusal.STATE_REJECTED, REQUESTED_STATE
                        + " is one of " + SuspensionState.spellings() + ", which is what anybody"
                        + " may ask for. The platform may report more than those, because an"
                        + " instance can finish while the request is in flight."));
    }

    private static Outcome read(DocumentValue arguments, AgentContract contract,
                                List<String> members) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming one workflow instance");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !members.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(INSTANCE_IDENTIFIER).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    INSTANCE_IDENTIFIER + " is required; this command chooses no instance");
        }
        final long bound =
                contract.value(ContractLimit.MAXIMUM_WORKFLOW_INSTANCE_IDENTIFIER_BYTES);
        if (!(mapping.member(INSTANCE_IDENTIFIER).orElseThrow()
                instanceof final DocumentValue.Text identifier)
                || identifier.value().isEmpty() || identifier.value().length() > bound) {
            return new Refused(Refusal.IDENTIFIER_REJECTED, INSTANCE_IDENTIFIER + " is what the"
                    + " platform calls one instance: not empty, and within the " + bound
                    + " an identifier may be");
        }
        return new Held(new WorkflowInstanceCommand(identifier.value()));
    }
}
