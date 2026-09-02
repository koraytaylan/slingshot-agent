// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the experience fragment went, and where its first variation went.
 *
 * <p>Both, because an experience fragment is not one thing. The fragment is a container and the
 * variation is what actually renders, and a caller given only the container's address would
 * address the container in the next command and find nothing to change. Saying both costs one
 * member and saves that whole round trip.</p>
 */
public final class CreateExperienceFragmentResult {

    private CreateExperienceFragmentResult() {
    }

    /** The member the fragment's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** The member the first variation's address is carried in. */
    public static final String VARIATION_PATH = "variation_path";

    /** Every member this result's document has, and there is no third. */
    public static final List<String> MEMBERS = List.of(REPOSITORY_PATH, VARIATION_PATH);

    /**
     * The result one creation produces.
     *
     * @param repositoryPath where the fragment went
     * @param variationPath where its first variation went
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath, String variationPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        result.put(VARIATION_PATH, new DocumentValue.Text(variationPath));
        return new DocumentValue.Mapping(result);
    }
}
