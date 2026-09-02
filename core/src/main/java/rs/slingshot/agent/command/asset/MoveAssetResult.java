// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the asset went, where it came from, and how many references moved with it.
 *
 * <p>The same three things a page's move reports, because it is the same question about a different
 * kind of thing — and an asset is referenced from more places than a page usually is, so the count
 * is the number that tells somebody whether they have just changed one page or four hundred.</p>
 */
public final class MoveAssetResult {

    private MoveAssetResult() {
    }

    /** The member the asset's old address is carried in. */
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
     * @param sourcePath where the asset was
     * @param destinationPath where it is
     * @param adjustedReferenceCount how many references were pointed at the new address
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
