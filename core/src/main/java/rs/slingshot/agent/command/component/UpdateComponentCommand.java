// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.List;
import rs.slingshot.agent.command.mutation.PropertyChange;
import rs.slingshot.agent.command.mutation.PropertyValue;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which component to change, and what to change about it.
 *
 * <p>Its members are its own and its reading is shared. The three commands that name one component
 * read the address identically, and a second reader for it would be a second chance for "which
 * component" to come to mean something slightly different — but each of the three declares only the
 * members its own document has, because a model that claimed all of them would claim members the
 * client's schema for this command does not carry.</p>
 */
public final class UpdateComponentCommand {

    private UpdateComponentCommand() {
    }

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "update_component";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS = List.of(ComponentPathCommand.COMPONENT_PATH,
            PropertyChange.PROPERTIES, PropertyValue.CARDINALITY,
            PropertyChange.REMOVED_PROPERTY_NAMES);

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract
     * @return the command, or the one reason there is none
     */
    public static ComponentPathCommand.Outcome of(DocumentValue arguments,
                                                  AgentContract contract) {
        return ComponentPathCommand.of(ComponentPathCommand.Shape.UPDATE, arguments, contract);
    }
}
