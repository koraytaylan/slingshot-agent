// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One anchor's immediate children of one primary type, and which window of them is wanted.
 *
 * <p>This is the read a page listing narrows: the same anchor, window, and ordering, admitting
 * only the children that are exactly the type the caller named. The page listing is this command
 * with {@code cq:Page}, which is why the two agree on every rule but the filter.</p>
 *
 * @param primaryNodeType the exact primary type a child must have to match
 * @param rootPath the node whose immediate children are wanted
 * @param window which page of those children is wanted
 */
public record ListChildNodesByTypeCommand(String primaryNodeType, String rootPath,
                                          ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_child_nodes_by_type";

    /** The member the anchor's address is carried in, spelled as the client spells it. */
    public static final String ROOT_PATH = "root_path";

    /** The member the required primary type is carried in, spelled as the client spells it. */
    public static final String PRIMARY_NODE_TYPE = "primary_node_type";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(PRIMARY_NODE_TYPE, ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /** The members a caller has to send. */
    public static final List<String> REQUIRED = List.of(PRIMARY_NODE_TYPE, ROOT_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The anchor is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
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
    public record Held(ListChildNodesByTypeCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the window and the token
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        final ChildListingArgument.Outcome read = ChildListingArgument.read(arguments, contract,
                MEMBERS, REQUIRED);
        if (read instanceof final ChildListingArgument.Refused refused) {
            return new Refused(Refusal.valueOf(refused.refusal().name()), refused.detail());
        }
        final ChildListingArgument.Accepted accepted =
                ((ChildListingArgument.Read) read).accepted();
        return new Held(new ListChildNodesByTypeCommand(
                ChildListingArgument.primaryNodeType(arguments),
                accepted.rootPath(), accepted.window()));
    }
}
