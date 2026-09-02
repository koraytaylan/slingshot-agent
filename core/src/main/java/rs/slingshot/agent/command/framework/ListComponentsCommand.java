// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.ComponentState;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which components to list, and which page of them.
 *
 * <p>The same two filters the bundle listing takes, over the states a component has rather than the
 * ones a bundle has. Those two sets look similar and are not the same question: a bundle is active
 * when the framework started it, and a component inside that bundle is unsatisfied when something
 * it requires is missing. The second is invisible from the first, which is why this exists.</p>
 *
 * @param prefix what a component name begins with, which is {@link #EVERY_COMPONENT} for all
 * @param states which states to include, which is empty for every state
 * @param window which page is wanted
 */
public record ListComponentsCommand(String prefix, List<ComponentState> states,
                                    ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_open_service_gateway_initiative_components";

    /** The member the name prefix is carried in. */
    public static final String NAME_PREFIX = "name_prefix";

    /** The member the states to include are carried in. */
    public static final String STATES = "states";

    /** What the prefix says when the caller named none, which matches every component. */
    public static final String EVERY_COMPONENT = "";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(NAME_PREFIX, ResultWindow.ARGUMENT_MEMBER, STATES);

    /** The members a caller has to send, which is none: every one has a defensible default. */
    public static final List<String> REQUIRED = List.of();

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The prefix is empty, or longer than a component name may be. */
        PREFIX_REJECTED,
        /** A named state is not one of the four there are. */
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
    public record Held(ListComponentsCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /** Holds a listing whose states nothing can change afterwards. */
    public ListComponentsCommand {
        states = List.copyOf(states);
    }

    /**
     * Which states this listing includes.
     *
     * @return the states, which nothing may add to, and which is empty for all of them
     */
    @Override
    public List<ComponentState> states() {
        return states;
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the prefix and the window
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which components and which page of them");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final long bound =
                contract.value(ContractLimit.MAXIMUM_DECLARATIVE_SERVICE_COMPONENT_NAME_BYTES);
        final Optional<DocumentValue> asked = mapping.member(NAME_PREFIX);
        if (asked.isPresent()
                && (!(asked.orElseThrow() instanceof final DocumentValue.Text text)
                        || text.value().isEmpty() || text.value().length() > bound)) {
            return new Refused(Refusal.PREFIX_REJECTED, NAME_PREFIX + " is text a component name"
                    + " begins with: not empty, and within the " + bound + " one may be. Leave it"
                    + " out to see every component.");
        }
        final String prefix = asked
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(EVERY_COMPONENT);
        final Optional<List<ComponentState>> states = statesIn(mapping);
        if (states.isEmpty()) {
            return new Refused(Refusal.STATE_REJECTED, STATES + " is a list of the states to"
                    + " include, from " + ComponentState.spellings() + ". Leave it out to see"
                    + " every state, which is what an operator chasing a feature that does not"
                    + " work is actually looking for.");
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new ListComponentsCommand(prefix, states.orElseThrow(),
                        ((ResultWindow.Held) window).window()));
    }

    private static Optional<List<ComponentState>> statesIn(DocumentValue.Mapping mapping) {
        final Optional<DocumentValue> asked = mapping.member(STATES);
        if (asked.isEmpty()) {
            return Optional.of(List.of());
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence items)
                || items.items().isEmpty()) {
            return Optional.empty();
        }
        final List<ComponentState> states = items.items().stream()
                .filter(DocumentValue.Text.class::isInstance)
                .map(item -> ComponentState.named(((DocumentValue.Text) item).value()))
                .flatMap(Optional::stream)
                .toList();
        return states.size() == items.items().size() ? Optional.of(states) : Optional.empty();
    }
}
