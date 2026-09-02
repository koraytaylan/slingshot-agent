// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the asset went, and how large its original is.
 *
 * <p>The original's size and no word about renditions. The platform generates those afterwards, on
 * its own schedule, and an answer that mentioned them would be claiming something this command
 * cannot observe — a caller who then went looking for a thumbnail would find nothing and believe
 * the asset was broken.</p>
 */
public final class CreateAssetResult {

    private CreateAssetResult() {
    }

    /** The member the made asset's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the size of what was stored is carried in. */
    public static final String ORIGINAL_RENDITION_BYTE_LENGTH = "original_rendition_byte_length";

    /** Every member this result's document has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(ORIGINAL_RENDITION_BYTE_LENGTH, REPOSITORY_PATH);

    /**
     * The result one asset creation produces.
     *
     * @param repositoryPath where the asset went
     * @param originalByteLength how large what was stored is
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath,
                                                   long originalByteLength) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        result.put(ORIGINAL_RENDITION_BYTE_LENGTH, new DocumentValue.Whole(originalByteLength));
        return new DocumentValue.Mapping(result);
    }
}
