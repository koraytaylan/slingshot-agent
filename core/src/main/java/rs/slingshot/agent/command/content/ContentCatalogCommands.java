// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * The four catalogues that list what an author can place: component definitions, component
 * instances, content fragments, and experience fragments.
 *
 * <p>Each asks the same question of a different kind of node: which anchor, and which window of
 * what sits under it. The kind is the command's own, so it is not an argument.</p>
 */
public final class ContentCatalogCommands {

    private ContentCatalogCommands() {
    }

    /**
     * The component definitions under one anchor.
     *
     * @param rootPath the anchor to search under
     * @param window which page of the definitions is wanted
     */
    public record ListComponentDefinitionsCommand(String rootPath, ResultWindow window) {

        /** The command's own name on the wire. */
        public static final String WIRE_NAME = "list_component_definitions";

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

        /** An argument this command takes. */
        public record Held(ListComponentDefinitionsCommand command) implements Outcome {
        }

        /** One it does not. */
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
                case RootedWindow.Held held -> new Held(new ListComponentDefinitionsCommand(
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

    /**
     * The component instances under one anchor.
     *
     * @param rootPath the anchor to search under
     * @param window which page of the instances is wanted
     */
    public record ComponentInstancesCommand(String rootPath, ResultWindow window) {

        /** The command's own name on the wire. */
        public static final String WIRE_NAME = "list_components";

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

        /** An argument this command takes. */
        public record Held(ComponentInstancesCommand command) implements Outcome {
        }

        /** One it does not. */
        public record Refused(Refusal refusal, String detail) implements Outcome {
        }

        /**
         * Reads one caller's argument.
         *
         * @param arguments the argument document
         * @param contract the authenticated contract
         * @return the command, or the one reason there is none
         */
        public static Outcome of(DocumentValue arguments, AgentContract contract) {
            return switch (RootedWindow.of(arguments, contract)) {
                case RootedWindow.Held held -> new Held(new ComponentInstancesCommand(
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

    /**
     * The content fragments under one anchor.
     *
     * @param rootPath the anchor to search under
     * @param window which page of the fragments is wanted
     */
    public record ListContentFragmentsCommand(String rootPath, ResultWindow window) {

        /** The command's own name on the wire. */
        public static final String WIRE_NAME = "list_content_fragments";

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

        /** An argument this command takes. */
        public record Held(ListContentFragmentsCommand command) implements Outcome {
        }

        /** One it does not. */
        public record Refused(Refusal refusal, String detail) implements Outcome {
        }

        /**
         * Reads one caller's argument.
         *
         * @param arguments the argument document
         * @param contract the authenticated contract
         * @return the command, or the one reason there is none
         */
        public static Outcome of(DocumentValue arguments, AgentContract contract) {
            return switch (RootedWindow.of(arguments, contract)) {
                case RootedWindow.Held held -> new Held(new ListContentFragmentsCommand(
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

    /**
     * The experience fragments under one anchor.
     *
     * @param rootPath the anchor to search under
     * @param window which page of the fragments is wanted
     */
    public record ListExperienceFragmentsCommand(String rootPath, ResultWindow window) {

        /** The command's own name on the wire. */
        public static final String WIRE_NAME = "list_experience_fragments";

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

        /** An argument this command takes. */
        public record Held(ListExperienceFragmentsCommand command) implements Outcome {
        }

        /** One it does not. */
        public record Refused(Refusal refusal, String detail) implements Outcome {
        }

        /**
         * Reads one caller's argument.
         *
         * @param arguments the argument document
         * @param contract the authenticated contract
         * @return the command, or the one reason there is none
         */
        public static Outcome of(DocumentValue arguments, AgentContract contract) {
            return switch (RootedWindow.of(arguments, contract)) {
                case RootedWindow.Held held -> new Held(new ListExperienceFragmentsCommand(
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
}
