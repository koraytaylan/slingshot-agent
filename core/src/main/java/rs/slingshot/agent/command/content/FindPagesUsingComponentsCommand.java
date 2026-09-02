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
 * Which pages use any of these components, under one subtree.
 *
 * <p>The other migration question, and the harder one. A component appears anywhere inside a page
 * rather than as a property of it, so what matches is a descendant node and what the caller wants is
 * the page containing it.</p>
 *
 * <p>An empty component list is refused at construction rather than treated as "any". A query with
 * no restriction is a traversal of everything under the root, which is the one thing this build does
 * not do — and "match everything" is never what somebody meant when they sent no types.</p>
 *
 * @param rootPath the subtree to search, which bounds the search rather than describing it
 * @param resourceTypes the component resource types to look for, of which there is at least one
 * @param matchMode whether a page using any of them matches, or only one using all of them
 * @param window which page of the matches is wanted
 */
public record FindPagesUsingComponentsCommand(String rootPath, List<String> resourceTypes,
                                              MatchMode matchMode, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "find_pages_using_components";

    /** The member the subtree to search is carried in. */
    public static final String ROOT_PATH = "root_path";

    /** The member the component resource types are carried in. */
    public static final String RESOURCE_TYPES = "resource_types";

    /** The member saying whether any of the types is enough or all of them are needed. */
    public static final String MATCH_MODE = "match_mode";

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS =
            List.of(MATCH_MODE, RESOURCE_TYPES, ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /** The members a caller has to send, the window being the one this side has a default for. */
    public static final List<String> REQUIRED = List.of(MATCH_MODE, RESOURCE_TYPES, ROOT_PATH);

    /**
     * Holds the types apart from whatever the caller still has a reference to.
     */
    public FindPagesUsingComponentsCommand {
        resourceTypes = List.copyOf(resourceTypes);
    }

    /** Why an argument is not one this command takes. */
    public enum Refusal {
        /** The argument is not an object. */
        NOT_A_DOCUMENT,
        /** A member this command needs is absent. */
        MEMBER_ABSENT,
        /** A member nobody declared is present. */
        MEMBER_UNKNOWN,
        /** The match mode is neither of the two there are. */
        UNKNOWN_MATCH_MODE,
        /** The root is not an absolute repository path. */
        NOT_AN_ABSOLUTE_PATH,
        /** The component types are not a list of text. */
        TYPES_NOT_A_LIST,
        /** The component list is empty, which would ask for every node under the root. */
        NO_COMPONENT_TYPES,
        /** More component types were asked for than this deployment looks for at once. */
        TOO_MANY_COMPONENT_TYPES,
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
    public record Held(FindPagesUsingComponentsCommand command) implements Outcome {
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
     * @param contract the authenticated contract, which bounds the list and the window
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
                    + " chooses neither a subtree, a set of components, nor a page for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ROOT_PATH).orElseThrow() instanceof final DocumentValue.Text root)
                || root.value().isEmpty() || root.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ROOT_PATH + " is an absolute path beginning at the root");
        }
        if (!(mapping.member(RESOURCE_TYPES).orElseThrow()
                instanceof final DocumentValue.Sequence types)) {
            return new Refused(Refusal.TYPES_NOT_A_LIST, RESOURCE_TYPES + " is a list of types");
        }
        if (types.items().stream().anyMatch(item -> !(item instanceof DocumentValue.Text))) {
            return new Refused(Refusal.TYPES_NOT_A_LIST,
                    RESOURCE_TYPES + " holds something that is not a resource type");
        }
        if (types.items().isEmpty()) {
            return new Refused(Refusal.NO_COMPONENT_TYPES, RESOURCE_TYPES + " is empty. A search"
                    + " for no component in particular is a walk of every node under the root, and"
                    + " match-everything is never what an empty list meant.");
        }
        final long bound = contract.value(ContractLimit.MAXIMUM_REQUESTED_COMPONENT_RESOURCE_TYPES);
        if (types.items().size() > bound) {
            return new Refused(Refusal.TOO_MANY_COMPONENT_TYPES, types.items().size()
                    + " component types is more than the " + bound + " this deployment looks for"
                    + " at once");
        }
        if (!(mapping.member(MATCH_MODE).orElseThrow() instanceof final DocumentValue.Text asked)) {
            return new Refused(Refusal.UNKNOWN_MATCH_MODE, MATCH_MODE + " is not text");
        }
        final Optional<MatchMode> mode = MatchMode.named(asked.value());
        if (mode.isEmpty()) {
            return new Refused(Refusal.UNKNOWN_MATCH_MODE, asked.value() + " is neither "
                    + MatchMode.ANY.spelling() + " nor " + MatchMode.ALL.spelling());
        }
        return windowed(root.value(), types.items().stream()
                .map(item -> ((DocumentValue.Text) item).value())
                .toList(), mode.orElseThrow(), mapping, contract);
    }

    private static Outcome windowed(String root, List<String> types, MatchMode mode,
                                    DocumentValue.Mapping mapping, AgentContract contract) {
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new FindPagesUsingComponentsCommand(root, types, mode,
                        ((ResultWindow.Held) window).window()));
    }

    /**
     * The component types this command looks for, which nothing may add to.
     *
     * @return the types
     */
    @Override
    public List<String> resourceTypes() {
        return java.util.Collections.unmodifiableList(resourceTypes);
    }
}
