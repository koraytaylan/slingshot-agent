// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/** Where the folder actually went. */
public final class CreateAssetFolderResult {

    private CreateAssetFolderResult() {
    }

    /** The member the made folder's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(REPOSITORY_PATH);

    /**
     * The result one folder creation produces.
     *
     * @param repositoryPath where the folder went
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        return new DocumentValue.Mapping(result);
    }
}
