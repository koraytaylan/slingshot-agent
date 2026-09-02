// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.List;
import java.util.Optional;
import rs.slingshot.agent.command.ResultWindow;
import rs.slingshot.agent.command.search.PropertyPredicate;
import rs.slingshot.agent.contract.AgentContract;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which addresses one caller asked for: a root, a node type, and which page of them.
 *
 * <p>The simplest paged command there is, which is why the paging machinery is proved on it. It
 * answers addresses and nothing else, so anything beyond an address appearing in a result would be
 * unmistakable rather than buried among fields a reader skims past.</p>
 *
 * <p>There is no separate continuation member. Where a caller resumes, they say so in the window —
 * which carries the token and nothing else, so a caller cannot resume and restate a page size in
 * one breath. A command with both a window and a token beside it would have two places to say
 * where to start and no rule about which wins.</p>
 *
 * @param rootPath the subtree to search, which bounds the search rather than describing it
 * @param primaryNodeType the node type to return, which is what makes the query answerable from an
 *     index rather than by walking, and is empty where the caller named none
 * @param predicates what else the caller asks about each candidate, applied to the rows the query
 *     returns rather than being part of it
 * @param window which page of the addresses is wanted
 */
public record QueryPathsCommand(String rootPath, String primaryNodeType,
                                List<PropertyPredicate> predicates, ResultWindow window) {

    /** The command's own name on the wire, which its registry row states and this repeats. */
    public static final String WIRE_NAME = "query_paths";

    /** The member the root is carried in. */
    public static final String ROOT_PATH = "root_path";

    /** The member the node type is carried in. */
    public static final String PRIMARY_NODE_TYPE = "primary_node_type";

    /** Every member this command's argument has, and there is no fifth. */
    public static final List<String> MEMBERS = List.of(PRIMARY_NODE_TYPE,
            PropertyPredicate.ARGUMENT_MEMBER, ResultWindow.ARGUMENT_MEMBER, ROOT_PATH);

    /**
     * The member a caller has to send.
     *
     * <p>Only the root. A search with no type and no predicate is every node under the root, which
     * is a question this command can answer from an index and a caller is entitled to ask.</p>
     */
    public static final List<String> REQUIRED = List.of(ROOT_PATH);

    /** Where a caller named no node type, which asks about every node under the root. */
    public static final String ANY_NODE_TYPE = "";

    /** Holds the predicates apart from whatever the caller still has a reference to. */
    public QueryPathsCommand {
        predicates = List.copyOf(predicates);
    }

    /**
     * What else this search asks about each candidate.
     *
     * @return the predicates, which nothing may add to
     */
    @Override
    public List<PropertyPredicate> predicates() {
        return java.util.Collections.unmodifiableList(predicates);
    }

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
        /** The node type is not one a repository could hold. */
        NOT_A_NODE_TYPE,
        /** A predicate is not one this language defines. */
        PREDICATE_REFUSED,
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
    public record Held(QueryPathsCommand command) implements Outcome {
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
            return new Refused(Refusal.MEMBER_ABSENT,
                    absent.get() + " is required; this command chooses no subtree for a caller");
        }
        return read(mapping, contract);
    }

    private static Outcome read(DocumentValue.Mapping mapping, AgentContract contract) {
        if (!(mapping.member(ROOT_PATH).orElseThrow() instanceof final DocumentValue.Text root)
                || root.value().isEmpty() || root.value().charAt(0) != '/') {
            return new Refused(Refusal.NOT_AN_ABSOLUTE_PATH,
                    ROOT_PATH + " is an absolute path beginning at the root");
        }
        final Optional<DocumentValue> named = mapping.member(PRIMARY_NODE_TYPE);
        if (named.isPresent() && (!(named.orElseThrow() instanceof final DocumentValue.Text type)
                || type.value().isBlank())) {
            return new Refused(Refusal.NOT_A_NODE_TYPE, PRIMARY_NODE_TYPE + " was sent and names"
                    + " nothing; a caller who wants every node sends no type at all");
        }
        return predicated(root.value(), named
                .map(value -> ((DocumentValue.Text) value).value())
                .orElse(ANY_NODE_TYPE), mapping, contract);
    }

    private static Outcome predicated(String root, String type, DocumentValue.Mapping mapping,
                                      AgentContract contract) {
        final PropertyPredicate.Outcome predicates = PropertyPredicate.listOf(mapping, contract);
        if (predicates instanceof final PropertyPredicate.Refused refused) {
            return new Refused(Refusal.PREDICATE_REFUSED,
                    refused.refusal() + ": " + refused.detail());
        }
        return windowed(root, type, ((PropertyPredicate.Held) predicates).predicates(), mapping,
                contract);
    }

    private static Outcome windowed(String root, String type, List<PropertyPredicate> predicates,
                                    DocumentValue.Mapping mapping, AgentContract contract) {
        final ResultWindow.Outcome window = ResultWindow.asked(mapping, contract);
        return window instanceof final ResultWindow.Refused refused
                ? new Refused(Refusal.WINDOW_REFUSED, refused.refusal().toString())
                : new Held(new QueryPathsCommand(root, type, predicates,
                        ((ResultWindow.Held) window).window()));
    }
}
