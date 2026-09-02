// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the page actually went.
 *
 * <p>The address the page has rather than a yes. A caller comparing it against the one they asked
 * for catches a whole class of defect a boolean cannot: a name the repository altered because
 * something was already there, a parent that resolved somewhere other than where they meant. The
 * cost of saying it is one member; the cost of not saying it is a caller who believes a page is
 * somewhere it is not.</p>
 */
public final class CreatePageResult {

    private CreatePageResult() {
    }

    /** The member the created page's address is carried in. */
    public static final String TARGET_PATH = "target_path";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(TARGET_PATH);

    /**
     * The result one creation produces.
     *
     * @param targetPath where the page went
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String targetPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(TARGET_PATH, new DocumentValue.Text(targetPath));
        return new DocumentValue.Mapping(result);
    }
}
