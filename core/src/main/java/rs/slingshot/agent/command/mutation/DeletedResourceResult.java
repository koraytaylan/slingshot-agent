// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.mutation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What every delete answers: what was removed, and how much of it there was.
 *
 * <p>Four commands answer this shape — a page, a component, an asset, a fragment — and they answer
 * it identically because the question is identical. The count is there because a caller who asked
 * to delete one page and is told nine hundred nodes went with it has learned something they needed
 * to know before the next one.</p>
 */
public final class DeletedResourceResult {

    private DeletedResourceResult() {
    }

    /** The member the removed address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the count of removed nodes is carried in. */
    public static final String REMOVED_NODE_COUNT = "removed_node_count";

    /** Every member this result's document has, and there is no third. */
    public static final List<String> MEMBERS = List.of(REMOVED_NODE_COUNT, REPOSITORY_PATH);

    /**
     * The result one deletion produces.
     *
     * @param repositoryPath what was removed, echoed so the answer says what it is about
     * @param removedNodeCount how many nodes went with it, the addressed one included
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath, long removedNodeCount) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        result.put(REMOVED_NODE_COUNT, new DocumentValue.Whole(removedNodeCount));
        return new DocumentValue.Mapping(result);
    }
}
