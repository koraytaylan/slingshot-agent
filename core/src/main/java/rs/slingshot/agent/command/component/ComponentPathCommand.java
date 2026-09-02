// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.mutation.ComponentPlacement;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The three commands that name one component and say what to do with it.
 *
 * <p>Read together because the naming is identical and only what follows differs: a change carries
 * two property lists, a reorder carries a placement, and a removal carries nothing at all. Three
 * readers would be three chances for "which component" to come to mean something slightly different
 * in each — and the address is the part every one of them gets wrong the same way if it is wrong.
 * </p>
 *
 * @param componentPath the component this command is about
 * @param change what to write and take away, which is nothing but for an update
 */
public record ComponentPathCommand(String componentPath, PropertyChange change) {

    /** The member the component's address is carried in. */
    public static final String COMPONENT_PATH = "component_path";

    /** Every member an update's argument has, and there is no fourth. */
    public static final List<String> UPDATE_MEMBERS = List.of(COMPONENT_PATH,
            PropertyChange.PROPERTIES, PropertyValue.CARDINALITY,
            PropertyChange.REMOVED_PROPERTY_NAMES);

    /** Every member a removal's argument has, and there is no second. */
    public static final List<String> DELETE_MEMBERS = List.of(COMPONENT_PATH);

    /** Every member a reorder's argument has, and there is no third. */
    public static final List<String> REORDER_MEMBERS =
            List.of(ComponentPlacement.ARGUMENT_MEMBER, COMPONENT_PATH, ComponentPlacement.MODE);

    /** What one of these three commands does with the component it names. */
    public enum Shape {
        /** Writes and removes properties on it. */
        UPDATE(UPDATE_MEMBERS),
        /** Removes it. */
        DELETE(DELETE_MEMBERS),
        /** Moves it among its siblings. */
        REORDER(REORDER_MEMBERS);

        private final List<String> members;

        Shape(List<String> members) {
            this.members = members;
        }

        /**
         * Every member this shape's argument has.
         *
         * @return the members
         */
        public List<String> members() {
            return java.util.Collections.unmodifiableList(members);
        }
    }

    /** Why an argument is not one these commands take. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The component is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The change is not one this contract makes. */
        CHANGE_REJECTED,
        /** The placement is not one this contract defines. */
        PLACEMENT_REJECTED
    }

    /**
     * The result of reading one: the command, or the one reason there is none.
     *
     * <p>A reorder's answer carries its placement and the other two do not, rather than all three
     * carrying a place that two of them leave absent. An absence every reader has to remember not
     * to look at is an absence somebody eventually looks at.</p>
     */
    public sealed interface Outcome permits Held, Placed, Refused {
    }

    /**
     * An argument a change or a removal takes.
     *
     * @param command what was asked
     */
    public record Held(ComponentPathCommand command) implements Outcome {
    }

    /**
     * An argument a reorder takes, which says where the component goes.
     *
     * @param command what was asked
     * @param placement where it goes
     */
    public record Placed(ComponentPathCommand command, ComponentPlacement placement)
            implements Outcome {
    }

    /**
     * One they do not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument for one of the three shapes.
     *
     * @param shape which of the three this is
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the address and the lists
     * @return the command, or the one reason there is none
     */
    public static Outcome of(Shape shape, DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object naming a"
                    + " component and saying what to do with it");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !shape.members().contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        if (mapping.member(COMPONENT_PATH).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    COMPONENT_PATH + " is required; this command chooses no component for a"
                            + " caller");
        }
        if (!(mapping.member(COMPONENT_PATH).orElseThrow()
                instanceof final DocumentValue.Text component)
                || component.value().isEmpty() || component.value().charAt(0) != '/'
                || component.value().length() > contract.value(
                        ContractLimit.MAXIMUM_REPOSITORY_PATH_BYTES)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    COMPONENT_PATH + " is an absolute path beginning at the root");
        }
        return shaped(shape, component.value(), mapping, contract);
    }

    private static Outcome shaped(Shape shape, String component, DocumentValue.Mapping mapping,
                                  AgentContract contract) {
        return switch (shape) {
            case UPDATE -> changed(component, mapping, contract);
            case DELETE -> new Held(new ComponentPathCommand(component, PropertyChange.NOTHING));
            case REORDER -> placed(component, mapping, contract);
        };
    }

    private static Outcome changed(String component, DocumentValue.Mapping mapping,
                                   AgentContract contract) {
        final PropertyChange.Outcome change = PropertyChange.of(mapping, contract);
        return change instanceof final PropertyChange.Refused refused
                ? new Refused(Refusal.CHANGE_REJECTED, refused.refusal() + ": " + refused.detail())
                : new Held(new ComponentPathCommand(component,
                        ((PropertyChange.Held) change).change()));
    }

    private static Outcome placed(String component, DocumentValue.Mapping mapping,
                                  AgentContract contract) {
        final Optional<DocumentValue> asked = mapping.member(ComponentPlacement.ARGUMENT_MEMBER);
        if (asked.isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT, ComponentPlacement.ARGUMENT_MEMBER
                    + " is required; a reorder that named no place would be a request to move a"
                    + " component somewhere unspecified");
        }
        final ComponentPlacement.Outcome placement =
                ComponentPlacement.of(asked.orElseThrow(), contract);
        return placement instanceof final ComponentPlacement.Refused refused
                ? new Refused(Refusal.PLACEMENT_REJECTED,
                        refused.refusal() + ": " + refused.detail())
                : new Placed(new ComponentPathCommand(component, PropertyChange.NOTHING),
                        ((ComponentPlacement.Held) placement).placement());
    }
}
