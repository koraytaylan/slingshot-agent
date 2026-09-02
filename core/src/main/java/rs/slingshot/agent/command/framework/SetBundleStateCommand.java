// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which bundle to act on, and what to do to it.
 *
 * <p>A transition rather than a target state, and the difference matters. Asking for
 * <em>active</em> would be asking this side to work out how to get there and to keep trying; asking
 * to <em>start</em> is one action with one answer. What the bundle ended up in is reported rather
 * than assumed, because a bundle asked to start very often ends up resolved instead — one of its
 * components would not activate — and that is precisely the thing the operator needs told.</p>
 *
 * <p>Refreshing is in the same set and it is the dangerous one: it restarts every bundle wired to
 * this one, which on an author instance can be most of them. It is named explicitly rather than
 * being something start quietly does.</p>
 *
 * @param symbolicName which bundle
 * @param transition what to do to it
 */
public record SetBundleStateCommand(String symbolicName, BundleInventory.Transition transition) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "set_open_service_gateway_initiative_bundle_state";

    /** The member the bundle's symbolic name is carried in. */
    public static final String SYMBOLIC_NAME = "symbolic_name";

    /** The member the transition is carried in. */
    public static final String TRANSITION = "transition";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS = List.of(SYMBOLIC_NAME, TRANSITION);

    /** The members a caller has to send, which is both: neither has a defensible default. */
    public static final List<String> REQUIRED = MEMBERS;

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The symbolic name is empty, or longer than one may be. */
        NAME_REJECTED,
        /** The transition is not one of the three there are. */
        TRANSITION_REJECTED
    }

    /** The result of reading one: the command, or the one reason there is none. */
    public sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument this command takes.
     *
     * @param command what was asked
     */
    public record Held(SetBundleStateCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the symbolic name
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object naming a bundle and what to do to it");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final Optional<String> absent = REQUIRED.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; refreshing a"
                    + " bundle restarts everything wired to it, so which action it is is not"
                    + " something this side may choose");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final long bound = contract.value(ContractLimit.MAXIMUM_BUNDLE_SYMBOLIC_NAME_BYTES);
        if (!(mapping.member(SYMBOLIC_NAME).orElseThrow() instanceof final DocumentValue.Text name)
                || name.value().isEmpty() || name.value().length() > bound) {
            return new Refused(Refusal.NAME_REJECTED, SYMBOLIC_NAME + " is what a bundle is called:"
                    + " not empty, and within the " + bound + " a symbolic name may be");
        }
        if (!(mapping.member(TRANSITION).orElseThrow() instanceof final DocumentValue.Text asked)) {
            return new Refused(Refusal.TRANSITION_REJECTED,
                    TRANSITION + " is one of " + BundleInventory.Transition.spellings());
        }
        return BundleInventory.Transition.named(asked.value())
                .<Outcome>map(transition -> new Held(
                        new SetBundleStateCommand(name.value(), transition)))
                .orElseGet(() -> new Refused(Refusal.TRANSITION_REJECTED, asked.value()
                        + " is not something this command does; they are "
                        + BundleInventory.Transition.spellings()));
    }
}
