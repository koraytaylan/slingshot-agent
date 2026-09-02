// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.List;
import rs.slingshot.agent.command.mutation.MoveRequest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which page to move, where to, and whether the links that point at it come too.
 *
 * <p>Its members are its own and its reading is shared with the asset move, because moving a page
 * and moving an asset are the same question about different things. What differs is what each can
 * fail with, and that lives in the handlers and the rows rather than here.</p>
 */
public final class MovePageCommand {

    private MovePageCommand() {
    }

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "move_page";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS = MoveRequest.MEMBERS;

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds both addresses
     * @return the command, or the one reason there is none
     */
    public static MoveRequest.Outcome of(DocumentValue arguments, AgentContract contract) {
        return MoveRequest.of(arguments, contract);
    }
}
