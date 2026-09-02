// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where a reordered component ends up, said as a neighbour rather than as a number.
 *
 * <p>A position by index is a race with whoever else is editing the page: the third slot when the
 * request was written is the fourth by the time it arrives, and the component lands somewhere
 * nobody asked for. Naming the component it should end up before is stable under any edit that does
 * not remove that neighbour — and where it does, the answer is a refusal naming what is missing,
 * which is something an author can act on.</p>
 *
 * <p>Last is a shape of its own rather than an absent neighbour. "Put it at the end" and "put it
 * before nothing in particular" would otherwise be the same message, and only one of them is a
 * caller who decided.</p>
 */
public sealed interface ComponentPlacement
        permits ComponentPlacement.Before, ComponentPlacement.Last {

    /** The member a caller states this in. */
    String ARGUMENT_MEMBER = "placement";

    /** The member saying which of the two shapes a placement has. */
    String MODE = "mode";

    /** How the shape naming a neighbour is spelled. */
    String BEFORE_MODE = "before";

    /** How the shape asking for the end is spelled. */
    String LAST_MODE = "last";

    /** The member the neighbour's name is carried in. */
    String SIBLING_NAME = "sibling_name";

    /** Every member a placement's own document has, which the reorder command borrows. */
    List<String> MEMBERS = List.of(MODE, SIBLING_NAME);

    /**
     * Put it immediately before this neighbour.
     *
     * @param siblingName the neighbour's own name among its parent's children
     */
    record Before(String siblingName) implements ComponentPlacement {
    }

    /** Put it at the end, whatever is there. */
    record Last() implements ComponentPlacement {
    }

    /** Why a placement is not one this contract defines. */
    enum Refusal {
        /** The placement is not an object. */
        NOT_A_DOCUMENT,
        /** The mode is neither of the two this contract defines. */
        UNKNOWN_MODE,
        /** A placement before a neighbour named none, or named one nobody could have. */
        SIBLING_REJECTED,
        /** A placement carried a member its mode does not take. */
        FIELDS_DO_NOT_MATCH_MODE
    }

    /** The result of reading one: the placement, or the one reason there is none. */
    sealed interface Outcome permits Held, Refused {
    }

    /**
     * A placement this contract defines.
     *
     * @param placement where the component goes
     */
    record Held(ComponentPlacement placement) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads the placement one caller wrote down.
     *
     * @param written the placement as the caller wrote it
     * @param contract the authenticated contract, which bounds a component's name
     * @return the placement, or the one reason there is none
     */
    static Outcome of(DocumentValue written, AgentContract contract) {
        if (!(written instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "a placement says where the component goes and then says where");
        }
        if (!(mapping.member(MODE).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text mode)) {
            return new Refused(Refusal.UNKNOWN_MODE, MODE + " is " + BEFORE_MODE + " or "
                    + LAST_MODE);
        }
        return switch (mode.value()) {
            case BEFORE_MODE -> before(mapping, contract);
            case LAST_MODE -> last(mapping);
            default -> new Refused(Refusal.UNKNOWN_MODE,
                    mode.value() + " is neither " + BEFORE_MODE + " nor " + LAST_MODE);
        };
    }

    private static Outcome before(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(SIBLING_NAME).orElse(new DocumentValue.Nothing())
                instanceof final DocumentValue.Text sibling) || sibling.value().isBlank()) {
            return new Refused(Refusal.SIBLING_REJECTED, SIBLING_NAME + " names the component this"
                    + " one goes before; a placement before nothing in particular is the end, which"
                    + " is said as " + LAST_MODE);
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_COMPONENT_NAME_BYTES);
        if (sibling.value().length() > bound) {
            return new Refused(Refusal.SIBLING_REJECTED, SIBLING_NAME + " is longer than the "
                    + bound + " a component's name may be");
        }
        return new Held(new Before(sibling.value()));
    }

    private static Outcome last(DocumentValue.Mapping mapping) {
        return mapping.member(SIBLING_NAME).isPresent()
                ? new Refused(Refusal.FIELDS_DO_NOT_MATCH_MODE, LAST_MODE + " asks for the end, so"
                        + " it names no neighbour; a member that is always ignored is a member"
                        + " somebody will eventually believe is honoured")
                : new Held(new Last());
    }

    /**
     * The name a placement asks to go before, where it asks to go before one.
     *
     * @param placement the placement
     * @return the neighbour's name, or nothing where the placement asks for the end
     */
    static Optional<String> siblingOf(ComponentPlacement placement) {
        return placement instanceof final Before before
                ? Optional.of(before.siblingName()) : Optional.empty();
    }
}
