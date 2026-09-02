// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.asset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/** Which asset's metadata was changed. */
public final class UpdateAssetMetadataResult {

    private UpdateAssetMetadataResult() {
    }

    /** The member the changed asset's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(REPOSITORY_PATH);

    /**
     * The result one metadata change produces.
     *
     * @param repositoryPath which asset it was
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        return new DocumentValue.Mapping(result);
    }
}
