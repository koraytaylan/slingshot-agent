// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which pages use one template, under one subtree.
 *
 * <p>This is the question behind most migrations: which pages would this change affect. It is
 * answerable from an index the platform already maintains, which is exactly why it must not be
 * answered any other way — the person asking it is about to change every page in the answer, and a
 * search that walked the repository to produce that list would take the instance down before the
 * migration started.</p>
 *
 * @param rootPath the subtree to search, which bounds the search rather than describing it
 * @param templatePath the template address whose pages are wanted
 * @param window which page of the matches is wanted
 */
public record FindPagesByTemplateCommand(String rootPath, String templatePath,
                                         ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_pages_by_template";

    /** The member the subtree to search is carried in. */
    public static final String ROOT_PATH = "root_path";

    /** The member the template address is carried in. */
    public static final String TEMPLATE_PATH = "template_path";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(ResultWindow.ARGUMENT_MEMBER, ROOT_PATH, TEMPLATE_PATH);

    /** The members a caller has to send, the window being the one with a default. */
    public static final List<String> REQUIRED = List.of(ROOT_PATH, TEMPLATE_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The root is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The template is not an absolute repository path either. */
        TEMPLATE_NOT_A_PATH,
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
    public record Held(FindPagesByTemplateCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the window
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object with three members");
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
                    + " chooses neither a subtree, a template, nor a page of matches for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ROOT_PATH).orElseThrow() instanceof final DocumentValue.Text root)
                || root.value().isEmpty() || root.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ROOT_PATH + " is an absolute path beginning at the root");
        }
        if (!(mapping.member(TEMPLATE_PATH).orElseThrow() instanceof final DocumentValue.Text template)
                || template.value().isEmpty() || template.value().charAt(0) != '/') {
            return new Refused(Refusal.TEMPLATE_NOT_A_PATH, TEMPLATE_PATH + " is the address of a"
                    + " template, which is an absolute path beginning at the root");
        }
        final ResultWindow.Outcome window =
                ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new FindPagesByTemplateCommand(root.value(), template.value(),
                        ((ResultWindow.Held) window).window()));
    }
}
