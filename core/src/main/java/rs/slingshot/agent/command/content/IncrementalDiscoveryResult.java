// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import java.util.LinkedHashMap;
import java.util.List;
import rs.slingshot.agent.json.DocumentValue;

/** The version 2 discovery result, retaining live provider order and explicit progress. */
public final class IncrementalDiscoveryResult {

    private IncrementalDiscoveryResult() {
    }

    /** The materialized rows. */
    public static final String MATCHES = "matches";

    /** Whether traversal exhausted every retained iterator. */
    public static final String COMPLETE = "complete";

    /** Nodes examined while producing this page. */
    public static final String EXAMINED_NODES = "examined_nodes";

    /** The position token of a partial page. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** A row's repository address. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** A component's declared or path-derived resource type. */
    public static final String RESOURCE_TYPE = "resource_type";

    /** A row's optional title. */
    public static final String TITLE = "title";

    /** Component result members, including nested row fields. */
    public static final List<String> COMPONENT_MEMBERS = List.of(MATCHES, COMPLETE, EXAMINED_NODES,
            NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH, RESOURCE_TYPE, TITLE);

    /** Page and fragment result members, including nested row fields. */
    public static final List<String> FRAGMENT_MEMBERS = List.of(MATCHES, COMPLETE, EXAMINED_NODES,
            NEXT_CONTINUATION_TOKEN, REPOSITORY_PATH, TITLE);

    /**
     * Writes a page whose bounds and token authority the owning session has checked.
     * @param rows detached rows in provider order
     * @param complete whether the traversal is exhausted
     * @param examined nodes visited during this page
     * @param token the next token, or empty on completion
     * @return the closed wire result
     * @throws IllegalArgumentException if completion contradicts the continuation
     */
    public static DocumentValue.Mapping documentOf(List<DocumentValue.Mapping> rows,
                                                    DocumentValue.Truth complete, long examined,
                                                    String token) {
        if ((complete == DocumentValue.Truth.TRUE) != token.isEmpty()) {
            throw new IllegalArgumentException("discovery completion must agree with its continuation");
        }
        final var members = new LinkedHashMap<String, DocumentValue>();
        members.put(MATCHES, new DocumentValue.Sequence(rows.stream()
                .map(DocumentValue.class::cast).toList()));
        members.put(COMPLETE, new DocumentValue.Flag(complete));
        members.put(EXAMINED_NODES, new DocumentValue.Whole(examined));
        if (!token.isEmpty()) {
            members.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(token));
        }
        return new DocumentValue.Mapping(members);
    }
}
