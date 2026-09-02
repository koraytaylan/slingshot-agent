// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.workflow;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.contract.ContractLimit;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which workflow models to list, and which page of them.
 *
 * <p>The filter is on the title rather than the identifier, which is the one place in this plan
 * where that is right. A model's identifier is a repository path nobody remembers; its title is
 * what appears in every screen anybody has ever seen it in, and an operator asking "is the review
 * workflow deployed" is asking about the title.</p>
 *
 * @param titlePrefix what a title begins with, which is {@link #EVERY_MODEL} for all of them
 * @param window which page is wanted
 */
public record ListWorkflowModelsCommand(String titlePrefix, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "list_workflow_models";

    /** The member the title prefix is carried in. */
    public static final String TITLE_PREFIX = "title_prefix";

    /** What the prefix says when the caller named none, which matches every model. */
    public static final String EVERY_MODEL = "";

    /** Every member this command's argument has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(ResultWindow.ARGUMENT_MEMBER, TITLE_PREFIX);

    /** The members a caller has to send, which is none: both have a defensible default. */
    public static final List<String> REQUIRED = List.of();

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The prefix is longer than a title may be. */
        PREFIX_REJECTED,
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
    public record Held(ListWorkflowModelsCommand command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds the prefix and the window
     * @return the command, or the one reason there is none
     */
    public static Outcome of(DocumentValue arguments, AgentContract contract) {
        if (!(arguments instanceof final DocumentValue.Mapping mapping)) {
            return new Refused(Refusal.NOT_A_DOCUMENT,
                    "an argument is an object saying which models and which page of them");
        }
        final Optional<String> unknown = mapping.members().keySet().stream()
                .filter(member -> !MEMBERS.contains(member))
                .findFirst();
        if (unknown.isPresent()) {
            return new Refused(Refusal.MEMBER_UNKNOWN,
                    unknown.get() + " is not a member of this command's argument");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_PAGE_TITLE_BYTES);
        final Optional<DocumentValue> asked = mapping.member(TITLE_PREFIX);
        if (asked.isPresent()
                && (!(asked.orElseThrow() instanceof final DocumentValue.Text text)
                        || text.value().length() > bound)) {
            return new Refused(Refusal.PREFIX_REJECTED, TITLE_PREFIX + " is text a title begins"
                    + " with, within the " + bound + " a title may be");
        }
        final String prefix = asked
                .filter(DocumentValue.Text.class::isInstance)
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(EVERY_MODEL);
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new ListWorkflowModelsCommand(prefix,
                        ((ResultWindow.Held) window).window()));
    }
}
