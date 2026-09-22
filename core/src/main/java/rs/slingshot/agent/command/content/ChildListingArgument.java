// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Reading one child listing's argument, for the three listings that share its shape.
 *
 * <p>Every child listing takes an anchor and an optional window and nothing else. The three
 * commands differ only in what they admit, so the reading is written once rather than three times:
 * a second copy would be a second answer to "may this window resume" on the day one of them started
 * reading a token differently.</p>
 */
final class ChildListingArgument {

    private ChildListingArgument() {
    }

    /** Why an argument was not one a listing takes. */
    enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member the command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The anchor is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The window is not one this contract defines. */
        WINDOW_REFUSED
    }

    /**
     * The anchor and window every child listing takes.
     *
     * @param rootPath the anchor
     * @param window the resolved window
     */
    record Accepted(String rootPath, ResultWindow window) {
    }

    /** The result of reading one: the values, or the one reason there is none. */
    sealed interface Outcome permits Read, Refused {
    }

    /**
     * Values a listing takes.
     *
     * @param accepted the values
     */
    record Read(Accepted accepted) implements Outcome {
    }

    /**
     * One a listing does not.
     *
     * @param refusal why not
     * @param detail what was seen, which names no content the caller cannot already see
     */
    record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one child listing's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the window and the token
     * @param members every member the command takes
     * @param required the members it requires
     * @return the values, or the one reason there is none
     */
    static Outcome read(DocumentValue arguments, AgentContract contract, List<String> members,
                        List<String> required) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !members.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final Optional<String> absent = required.stream()
                .filter(member -> mapping.member(member).isEmpty())
                .findFirst();
        if (absent.isPresent()) {
            return new Refused(Refusal.MEMBER_ABSENT, absent.get() + " is required; this command"
                    + " chooses no value for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        final DocumentValue anchor = mapping.member(ListChildNodesCommand.ROOT_PATH).orElseThrow();
        if (!(anchor instanceof final DocumentValue.Text path)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ListChildNodesCommand.ROOT_PATH + " is not text");
        }
        if (path.value().isEmpty() || path.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, ListChildNodesCommand.ROOT_PATH
                    + " is an absolute path beginning at the root");
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        if (window instanceof final ResultWindow.Refused refused) {
            return new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString());
        }
        return new Read(new Accepted(path.value(), ((ResultWindow.Held) window).window()));
    }

    /**
     * Reads the required primary type from one typed listing's argument.
     *
     * @param arguments the argument document
     * @return the type, or the one reason it is not usable
     */
    static Refusal typeRefusal(DocumentValue arguments) {
        if (arguments instanceof final DocumentValue.Mapping mapping) {
            return mapping.member(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE)
                    .filter(DocumentValue.Text.class::isInstance)
                    .map(held -> ((DocumentValue.Text) held).value())
                    .filter(value -> !value.isEmpty())
                    .isPresent() ? null : Refusal.MEMBER_ABSENT;
        }
        return Refusal.NOT_A_DOCUMENT;
    }

    /**
     * Reads the required primary type from one typed listing's argument.
     *
     * @param arguments the argument document
     * @return the type
     */
    static String primaryNodeType(DocumentValue arguments) {
        return ((DocumentValue.Text) ((DocumentValue.Mapping) arguments)
                .member(ListChildNodesByTypeCommand.PRIMARY_NODE_TYPE).orElseThrow()).value();
    }
}
