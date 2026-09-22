// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One anchor's immediate children, and which window of them is wanted.
 *
 * <p>This is the general read whose narrow form is {@link ListChildPagesCommand}: it admits every
 * child whatever its primary type, so a folder, a fragment, an asset, and a component beside the
 * pages are all listed rather than dropped. Listing children of a known node is a repository read,
 * which is what keeps it fast on a tree a query would have to walk.</p>
 *
 * @param rootPath the node whose immediate children are wanted
 * @param window which page of those children is wanted
 */
public record ListChildNodesCommand(String rootPath, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_child_nodes";

    /** The member the anchor's address is carried in, spelled as the client spells it. */
    public static final String ROOT_PATH = "root_path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /** The members a caller has to send. */
    public static final List<String> REQUIRED = List.of(ROOT_PATH);

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
    public record Held(ListChildNodesCommand command) implements Outcome {
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
        return new Held(new ListChildNodesCommand(accepted.rootPath(), accepted.window()));
    }
}
