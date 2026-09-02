// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.platform.BundleState;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which bundles to list, and which page of them.
 *
 * <p>Two filters, both of which narrow by something the framework already knows: what a bundle is
 * called and what state it is in. Naming no state means every state, which is right for the
 * ordinary case — an operator who filtered to {@code active} would see nothing wrong with an
 * instance whose problem is a bundle that never resolved.</p>
 *
 * @param prefix what a symbolic name begins with, which is {@link #EVERY_BUNDLE} for all of them
 * @param states which states to include, which is empty for every state
 * @param window which page is wanted
 */
public record ListBundlesCommand(String prefix, List<BundleState> states, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_open_service_gateway_initiative_bundles";

    /** The member the symbolic name prefix is carried in. */
    public static final String SYMBOLIC_NAME_PREFIX = "symbolic_name_prefix";

    /** The member the states to include are carried in. */
    public static final String STATES = "states";

    /** What the prefix says when the caller named none, which matches every bundle. */
    public static final String EVERY_BUNDLE = "";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(ResultWindow.ARGUMENT_MEMBER, STATES, SYMBOLIC_NAME_PREFIX);

    /** The members a caller has to send, which is none: every one has a defensible default. */
    public static final List<String> REQUIRED = List.of();

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The prefix is empty, or longer than a symbolic name may be. */
        PREFIX_REJECTED,
        /** A named state is not one of the six there are. */
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
    public record Held(ListBundlesCommand command) implements Outcome {
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
    public ListBundlesCommand {
        states = List.copyOf(states);
    }

    /**
     * Which states this listing includes.
     *
     * @return the states, which nothing may add to, and which is empty for all of them
     */
    @Override
    public List<BundleState> states() {
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
                    "an argument is an object saying which bundles and which page of them");
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
        final long bound = contract.value(ContractLimit.MAXIMUM_BUNDLE_SYMBOLIC_NAME_BYTES);
        final Optional<DocumentValue> asked = mapping.member(SYMBOLIC_NAME_PREFIX);
        if (asked.isPresent()
                && (!(asked.orElseThrow() instanceof final DocumentValue.Text text)
                        || text.value().isEmpty() || text.value().length() > bound)) {
            return new Refused(Refusal.PREFIX_REJECTED, SYMBOLIC_NAME_PREFIX + " is text a symbolic"
                    + " name begins with: not empty, and within the " + bound + " one may be."
                    + " Leave it out to see every bundle.");
        }
        final String prefix = asked
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(EVERY_BUNDLE);
        final Optional<List<BundleState>> states = statesIn(mapping);
        if (states.isEmpty()) {
            return new Refused(Refusal.STATE_REJECTED, STATES + " is a list of the states to"
                    + " include, from " + BundleState.spellings() + ". Leave it out to see every"
                    + " state: a caller who filtered to active would see nothing wrong with an"
                    + " instance whose problem is a bundle that never resolved.");
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new ListBundlesCommand(prefix, states.orElseThrow(),
                        ((ResultWindow.Held) window).window()));
    }

    private static Optional<List<BundleState>> statesIn(DocumentValue.Mapping mapping) {
        final Optional<DocumentValue> asked = mapping.member(STATES);
        if (asked.isEmpty()) {
            return Optional.of(List.of());
        }
        if (!(asked.orElseThrow() instanceof final DocumentValue.Sequence items)
                || items.items().isEmpty()) {
            return Optional.empty();
        }
        final List<BundleState> states = items.items().stream()
                .filter(DocumentValue.Text.class::isInstance)
                .map(item -> BundleState.named(((DocumentValue.Text) item).value()))
                .flatMap(Optional::stream)
                .toList();
        return states.size() == items.items().size() ? Optional.of(states) : Optional.empty();
    }
}
