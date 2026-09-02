// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.List;
import rs.slingshot.agent.command.mutation.ComponentPlacement;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which component to move among its siblings, and where to.
 *
 * <p>The placement is required. A reorder that named no place would be a request to move a
 * component somewhere unspecified, which is not something a repository can do and not something a
 * caller can have meant.</p>
 */
public final class ReorderComponentCommand {

    private ReorderComponentCommand() {
    }

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "reorder_component";

    /** Every member this command's argument has, the placement's own included. */
    public static final List<String> MEMBERS = List.of(ComponentPlacement.ARGUMENT_MEMBER,
            ComponentPathCommand.COMPONENT_PATH, ComponentPlacement.MODE);

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract
     * @return the command with its placement, or the one reason there is none
     */
    public static ComponentPathCommand.Outcome of(DocumentValue arguments,
                                                  AgentContract contract) {
        return ComponentPathCommand.of(ComponentPathCommand.Shape.REORDER, arguments, contract);
    }
}
