// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which component moved, and what it now sits behind.
 *
 * <p>The neighbour in front is the useful half. A caller asked to put something before a named
 * neighbour; what they need in order to check the page reads the way they meant is what ended up in
 * front of it, and that is read back from the parent rather than re-derived from the request.</p>
 *
 * <p>A component that is now first carries no neighbour at all. An empty name would read as a
 * neighbour whose name happens to be empty, which is a different claim.</p>
 */
public final class ReorderComponentResult {

    private ReorderComponentResult() {
    }

    /** The member the moved component's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the neighbour it now sits behind is carried in, where there is one. */
    public static final String PRECEDING_SIBLING_NAME = "preceding_sibling_name";

    /** Every member this result's document has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(PRECEDING_SIBLING_NAME, REPOSITORY_PATH);

    /** Where a component sits first among its siblings, with nothing in front of it. */
    public static final String NOTHING_IN_FRONT = "";

    /**
     * The result one reorder produces.
     *
     * @param repositoryPath which component moved
     * @param precedingSiblingName what it now sits behind, or {@link #NOTHING_IN_FRONT}
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath,
                                                   String precedingSiblingName) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        if (!NOTHING_IN_FRONT.equals(precedingSiblingName)) {
            result.put(PRECEDING_SIBLING_NAME, new DocumentValue.Text(precedingSiblingName));
        }
        return new DocumentValue.Mapping(result);
    }
}
