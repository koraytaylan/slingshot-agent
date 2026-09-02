// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which page was changed.
 *
 * <p>The address and nothing else. What was written is what the caller asked to write — this
 * command applies both lists whole or applies neither, so echoing them back would be repeating the
 * request rather than reporting the outcome. What a caller cannot know without being told is which
 * page it landed on, and that is what this says.</p>
 */
public final class UpdatePageResult {

    private UpdatePageResult() {
    }

    /** The member the changed page's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(REPOSITORY_PATH);

    /**
     * The result one update produces.
     *
     * @param repositoryPath the page that was changed
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        return new DocumentValue.Mapping(result);
    }
}
