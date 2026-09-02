// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One page's immediate children, and which window of them is wanted.
 *
 * <p>Navigating a site one level at a time is what an operator does most and what a query does
 * worst. This is a repository read: the children of a known node, in the order the repository holds
 * them. Keeping it a read rather than a search is what makes it fast on a tree a query would have
 * to walk, and it is why this command needs no index at all.</p>
 *
 * @param rootPath the page whose immediate children are wanted
 * @param window which page of those children is wanted
 */
public record ListChildPagesCommand(String rootPath, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_child_pages";

    /** The member the parent's address is carried in, spelled as the client spells it. */
    public static final String ROOT_PATH = "root_path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /**
     * The members a caller has to send.
     *
     * <p>Separate from every member there is, because the client's own schema requires one of the
     * two and this side does not get to require both: a caller who named a parent and no window
     * asked a complete question, and the answer to it is the first page.</p>
     */
    public static final List<String> REQUIRED = List.of(ROOT_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The parent is not an absolute repository path. */
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
    public record Held(ListChildPagesCommand command) implements Outcome {
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
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object with two members");
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
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; this command"
                    + " chooses no parent for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ROOT_PATH).orElseThrow() instanceof final DocumentValue.Text parent)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, ROOT_PATH + " is not text");
        }
        if (parent.value().isEmpty() || parent.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ROOT_PATH + " is an absolute path beginning at the root");
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new ListChildPagesCommand(parent.value(),
                        ((ResultWindow.Held) window).window()));
    }
}
