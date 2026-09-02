// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which assets one page references, and which kinds of reference to look for.
 *
 * <p>The kinds are stated rather than implied. A caller who asks about property values alone and
 * receives an empty answer knows exactly what that answer means; a caller who received the same
 * answer from a command that decided for itself what to look at would not. This matters because the
 * next thing somebody does with "this page references no assets" is delete assets.</p>
 *
 * @param pagePath the page whose references are wanted
 * @param window which page of the references is wanted
 */
public record FindAssetsReferencedByPageCommand(String pagePath, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_assets_referenced_by_page";

    /** The member the page's address is carried in. */
    public static final String PAGE_PATH = "page_path";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(PAGE_PATH, ResultWindow.ARGUMENT_MEMBER);

    /** The member a caller has to send, the window being the one with a default. */
    public static final List<String> REQUIRED = List.of(PAGE_PATH);

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The page is not an absolute repository path. */
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
    public record Held(FindAssetsReferencedByPageCommand command) implements Outcome {
    }

    /**
     * One it does not.
     *
     * @param refusal why it does not
     * @param detail what was seen, which for an unknown kind names what can be asked for
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
                    "an argument is an object with a page in it");
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
                    + " chooses no page for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(PAGE_PATH).orElseThrow() instanceof final DocumentValue.Text page)
                || page.value().isEmpty() || page.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    PAGE_PATH + " is an absolute path beginning at the root");
        }
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new FindAssetsReferencedByPageCommand(page.value(),
                        ((ResultWindow.Held) window).window()));
    }

}
