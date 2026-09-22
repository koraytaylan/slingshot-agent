// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The content fragment models under one anchor, and which window of them is wanted.
 *
 * <p>A model is the node directly inside {@code settings/dam/cfm/models}. That is the address
 * {@code create_content_fragment} takes, and the element declarations under it are part of the
 * model rather than another one.</p>
 *
 * @param rootPath the anchor to search under
 * @param window which page of the models is wanted
 */
public record ListContentFragmentModelsCommand(String rootPath, ResultWindow window) {

    /** The command's own name on the wire. */
    public static final String WIRE_NAME = "list_content_fragment_models";

    /** Every member this command's argument has. */
    public static final List<String> MEMBERS = RootedWindow.MEMBERS;

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
    public record Held(ListContentFragmentModelsCommand command) implements Outcome {
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
        return switch (RootedWindow.of(arguments, contract)) {
            case RootedWindow.Held held -> new Held(new ListContentFragmentModelsCommand(
                    held.asked().rootPath(), held.asked().window()));
            case RootedWindow.Refused refused -> new Refused(asRefusal(refused.refusal()),
                    refused.detail());
        };
    }

    private static Refusal asRefusal(RootedWindow.Refusal refusal) {
        return switch (refusal) {
            case NOT_A_DOCUMENT -> Refusal.NOT_A_DOCUMENT;
            case MEMBER_ABSENT -> Refusal.MEMBER_ABSENT;
            case MEMBER_UNKNOWN -> Refusal.MEMBER_UNKNOWN;
            case NOT_AN_ABSOLUTE_PATH -> Refusal.NOT_AN_ABSOLUTE_PATH;
            case WINDOW_REFUSED -> Refusal.WINDOW_REFUSED;
        };
    }
}
