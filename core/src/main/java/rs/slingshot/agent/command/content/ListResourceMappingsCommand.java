// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which window of the resolution rules is wanted.
 *
 * <p>There is no filter. A caller reading resolution rules is trying to understand what the
 * deployment does with an address, and rules interact: a filtered view shows some of them applying
 * and hides the ones that would have applied first, so the reader draws a conclusion that is wrong
 * about the very thing they were checking. A window is not a filter — it says how much to read at
 * once, not which rules exist — so paging is offered and filtering is not.</p>
 *
 * @param window which page of the rules is wanted
 */
public record ListResourceMappingsCommand(ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_resource_mappings";

    /** Every member this command's argument has, and there is no second. */
    public static final List<String> MEMBERS = List.of(ResultWindow.ARGUMENT_MEMBER);

    /** The members a caller has to send, which is none: the window has a default. */
    public static final List<String> REQUIRED = List.of();

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** The window is absent, and this command chooses no page for a caller. */
        MEMBER_ABSENT,
        /**
         * A member nobody declared is present.
         *
         * <p>Which is how a filter would arrive, and why one is refused rather than ignored: a
         * caller who sent one and was answered would believe they had read a filtered view.</p>
         */
        MEMBER_UNKNOWN,
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
    public record Held(ListResourceMappingsCommand command) implements Outcome {
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
            return new Refused(Refusal.NOT_A_DOCUMENT, "an argument is an object with one member");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN, unknown.get() + " is not a member of this"
                    + " command's argument. There is no filter: rules interact, so a filtered view"
                    + " hides the ones that would have applied first and the reader concludes"
                    + " something wrong about the very thing they were checking.");
        }
        if (mapping.member(ResultWindow.ARGUMENT_MEMBER).isEmpty()) {
            return new Refused(Refusal.MEMBER_ABSENT,
                    ResultWindow.ARGUMENT_MEMBER + " is required; this command chooses no page");
        }
        final ResultWindow.Outcome window =
                ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new ListResourceMappingsCommand(((ResultWindow.Held) window).window()));
    }
}
