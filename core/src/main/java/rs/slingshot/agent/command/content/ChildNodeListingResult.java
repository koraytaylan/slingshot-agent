// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * A list of child nodes: each an address, its primary type, and, where it has one, a title.
 *
 * <p>Its sibling {@link PageListingResult} carries pages, which is what a search answers. This
 * carries whichever children a listing admitted — every child, or the children of one primary type
 * — and reports the type beside the address, because the type is what tells a page from the folder
 * next to it. A listing that dropped it would leave a caller unable to say what it was looking at.
 *
 * <p>The member names are the client's, from the schema it publishes for both listings. They are
 * not this side's to choose: a result whose members are spelled differently is a result the other
 * half cannot read, however sensible the spelling.</p>
 */
public final class ChildNodeListingResult {

    private ChildNodeListingResult() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member one match's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member one match's primary type is carried in. */
    public static final String PRIMARY_NODE_TYPE = "primary_node_type";

    /** The member one match's title is carried in, where the node has one. */
    public static final String TITLE = "title";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /**
     * Every member this result's document has, nested ones included.
     */
    public static final List<String> MEMBERS = List.of(
            MATCHES, NEXT_CONTINUATION_TOKEN, PRIMARY_NODE_TYPE, REPOSITORY_PATH, TITLE);

    /**
     * One child node as a caller receives it.
     *
     * @param repositoryPath where it is
     * @param primaryNodeType what it is
     * @param title what it is called, which is empty where the node carries no title
     */
    public record Child(String repositoryPath, String primaryNodeType, String title) {
    }

    /**
     * The result one window of children produces.
     *
     * @param children the children, in whichever order the command that found them answers in
     * @param continuationToken the token reaching the next page, or empty where this is the end
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<Child> children,
                                                   String continuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(children.stream()
                .map(ChildNodeListingResult::childOf)
                .toList()));
        if (!continuationToken.isEmpty()) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(continuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    /**
     * The children of one listing, in the order the client's own contract requires.
     *
     * <p>The contract declares every child listing strictly ascending by repository-path bytes, so
     * the repository's order is not the answer: two pages must agree on one total order for a
     * continuation to mean anything, and Adobe's own order is not that order.</p>
     *
     * @param found the children, in whatever order the repository returned them
     * @return the same children, strictly ascending by path
     */
    public static List<Child> ascending(List<Child> found) {
        return found.stream()
                .sorted(Comparator.comparing(Child::repositoryPath))
                .toList();
    }

    private static DocumentValue childOf(Child child) {
        final SequencedMap<String, DocumentValue> node = new LinkedHashMap<>();
        node.put(PRIMARY_NODE_TYPE, new DocumentValue.Text(child.primaryNodeType()));
        node.put(REPOSITORY_PATH, new DocumentValue.Text(child.repositoryPath()));
        // A node with no title carries none rather than an empty one. The two are different claims
        // — "it is called nothing" and "it is called the empty string" — and the client's own
        // schema makes the member optional so that the first can be said.
        if (!child.title().isEmpty()) {
            node.put(TITLE, new DocumentValue.Text(child.title()));
        }
        return new DocumentValue.Mapping(node);
    }
}
