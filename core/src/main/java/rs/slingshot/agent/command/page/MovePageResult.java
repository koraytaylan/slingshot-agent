// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the page went, where it came from, and how many links moved with it.
 *
 * <p>The count is the part a caller could not work out for themselves, and it is the one that tells
 * them the scale of what just happened. A move that adjusted four references and a move that
 * adjusted four thousand are different events, and only one of them is worth telling somebody
 * about.</p>
 */
public final class MovePageResult {

    private MovePageResult() {
    }

    /** The member the page's old address is carried in. */
    public static final String SOURCE_PATH = "source_path";

    /** The member its new address is carried in. */
    public static final String DESTINATION_PATH = "destination_path";

    /** The member the count of adjusted references is carried in. */
    public static final String ADJUSTED_REFERENCE_COUNT = "adjusted_reference_count";

    /** Every member this result's document has, and there is no fourth. */
    public static final List<String> MEMBERS =
            List.of(ADJUSTED_REFERENCE_COUNT, DESTINATION_PATH, SOURCE_PATH);

    /**
     * The result one move produces.
     *
     * @param sourcePath where the page was
     * @param destinationPath where it is
     * @param adjustedReferenceCount how many links were pointed at the new address
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String sourcePath, String destinationPath,
                                                   long adjustedReferenceCount) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(SOURCE_PATH, new DocumentValue.Text(sourcePath));
        result.put(DESTINATION_PATH, new DocumentValue.Text(destinationPath));
        result.put(ADJUSTED_REFERENCE_COUNT, new DocumentValue.Whole(adjustedReferenceCount));
        return new DocumentValue.Mapping(result);
    }
}
