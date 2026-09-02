// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the component actually went.
 *
 * <p>The address rather than a yes. A caller comparing it against the parent and name they sent
 * catches the case where the repository put it somewhere else — under a parent that resolved
 * differently, or beside a name it had to alter.</p>
 */
public final class AddComponentResult {

    private AddComponentResult() {
    }

    /** The member the made component's address is carried in. */
    public static final String TARGET_PATH = "target_path";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(TARGET_PATH);

    /**
     * The result one addition produces.
     *
     * @param targetPath where the component went
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String targetPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(TARGET_PATH, new DocumentValue.Text(targetPath));
        return new DocumentValue.Mapping(result);
    }
}
