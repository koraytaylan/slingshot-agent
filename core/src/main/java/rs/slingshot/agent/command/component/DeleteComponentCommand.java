// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.List;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which component to remove.
 *
 * <p>One member, and no reference policy. A component is part of a page rather than something other
 * pages address, so the question the page and asset deletes have to ask — what about the things
 * pointing at it — has no answer to give here. The client's own schema says the same by carrying no
 * policy at all.</p>
 */
public final class DeleteComponentCommand {

    private DeleteComponentCommand() {
    }

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "delete_component";

    /** Every member this command's argument has, and there is no second. */
    public static final List<String> MEMBERS = List.of(ComponentPathCommand.COMPONENT_PATH);

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract
     * @return the command, or the one reason there is none
     */
    public static ComponentPathCommand.Outcome of(DocumentValue arguments,
                                                  AgentContract contract) {
        return ComponentPathCommand.of(ComponentPathCommand.Shape.DELETE, arguments, contract);
    }
}
