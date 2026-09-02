// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.List;
import rs.slingshot.agent.command.mutation.MoveRequest;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which asset to move, where to, and whether the references that point at it come too.
 *
 * <p>Its members are its own and its reading is shared with the page move. An asset is referenced
 * from more places than a page usually is, which is why the count of what moved matters more here —
 * but the question, and the mistake of naming a destination inside the source, are the same.</p>
 */
public final class MoveAssetCommand {

    private MoveAssetCommand() {
    }

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "move_asset";

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
