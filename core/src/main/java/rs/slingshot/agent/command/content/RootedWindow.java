// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The anchor and the window the two authoring catalogues share.
 *
 * <p>A page template listing and a content fragment model listing ask the same question of two
 * different folders: which node, and which page of what is under it. The folder is the command's
 * own, so it is not an argument. What is an argument is identical, and a second reading of it
 * would be a second answer to "what did the caller ask".</p>
 */
final class RootedWindow {

    private RootedWindow() {
    }

    /** The member the anchor is carried in. */
    static final String ROOT_PATH = "root_path";

    /** Every member both catalogues' arguments have. */
    static final List<String> MEMBERS = List.of(ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /** The member a caller has to send. A window left out is the first page. */
    static final List<String> REQUIRED = List.of(ROOT_PATH);

    /** Why an argument is not one these catalogues take. */
    enum Refusal {
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

    /**
     * An anchor and the window of results wanted under it.
     *
     * @param rootPath the anchor the catalogue is listed under
     * @param window which page of the catalogue is wanted
     */
    record Asked(String rootPath, ResultWindow window) {
    }

    /** The argument, or the one reason there is none. */
    sealed interface Outcome permits Held, Refused {
    }

    /**
     * An argument these catalogues take.
     *
     * @param asked the anchor and window it names
     */
    record Held(Asked asked) implements Outcome {
    }

    /**
     * One they do not.
     *
     * @param refusal which rule the argument broke
     * @param detail what was wrong with it, in words a caller can act on
     */
    record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the window and the token
     * @return the anchor and the window, or the one reason there is none
     */
    static Outcome of(DocumentValue arguments, AgentContract contract) {
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
                    + " chooses no anchor for a caller");
        }
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
                : new Held(new Asked(parent.value(), ((ResultWindow.Held) window).window()));
    }
}
