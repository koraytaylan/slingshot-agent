// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A phrase to look for, a subtree to look in, and which page of the matches is wanted.
 *
 * <p>Full-text search is where an unbounded query does the most damage, because the phrase comes
 * from a caller who has no idea what it will match. A phrase of two letters against a large site
 * matches most of it, and the caller did not mean to ask for most of it. So the phrase is bounded
 * as it arrives and the search is bounded as it runs, and the second of those refuses rather than
 * trims.</p>
 *
 * @param rootPath the subtree to search, which bounds the search rather than describing it
 * @param phrase the text to look for
 * @param window which page of the matches is wanted
 */
public record FindPagesContainingPhraseCommand(String rootPath, String phrase,
                                               ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_pages_containing_phrase";

    /** The member the subtree to search is carried in. */
    public static final String ROOT_PATH = "root_path";

    /** The member the phrase is carried in. */
    public static final String PHRASE = "phrase";

    /** Every member this command's argument has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(PHRASE, ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /** The members a caller has to send, the window being the one with a default. */
    public static final List<String> REQUIRED = List.of(PHRASE, ROOT_PATH);

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
        /** The phrase is empty, which asks for everything rather than for something. */
        PHRASE_EMPTY,
        /** The phrase is longer than this deployment searches for. */
        PHRASE_TOO_LONG,
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
    public record Held(FindPagesContainingPhraseCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which never repeats the caller's own phrase back into a log
     */
    public record Refused(Refusal refusal, String detail) implements Outcome {
    }

    /**
     * Reads one caller's argument.
     *
     * @param arguments the argument document
     * @param contract the authenticated contract, which bounds the phrase and the window
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
                    + " chooses neither a subtree, a phrase, nor a page of matches for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ROOT_PATH).orElseThrow() instanceof final DocumentValue.Text root)) {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH, ROOT_PATH + " is not text");
        }
        if (!(mapping.member(PHRASE).orElseThrow() instanceof final DocumentValue.Text phrase)) {
            return new Refused(Refusal.PHRASE_EMPTY, PHRASE + " is not text");
        }
        if (root.value().isEmpty() || root.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ROOT_PATH + " is an absolute path beginning at the root");
        }
        if (phrase.value().isBlank()) {
            return new Refused(Refusal.PHRASE_EMPTY, PHRASE + " is the text to look for, and an"
                    + " empty one asks for everything rather than for something");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_SEARCH_PHRASE_BYTES);
        if (phrase.value().length() > bound) {
            return new Refused(Refusal.PHRASE_TOO_LONG, PHRASE + " is longer than the " + bound
                    + " this deployment searches for");
        }
        final ResultWindow.Outcome window =
                ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new FindPagesContainingPhraseCommand(root.value(), phrase.value(),
                        ((ResultWindow.Held) window).window()));
    }
}
