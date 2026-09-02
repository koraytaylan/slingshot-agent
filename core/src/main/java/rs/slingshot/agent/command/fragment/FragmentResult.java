// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.fragment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Where the fragment that was made or changed actually is.
 *
 * <p>The address rather than a yes, for the reason every other creation answers an address: a
 * caller comparing what came back against what they asked for catches a name the repository
 * altered and a parent that resolved somewhere they did not mean, and a boolean catches
 * neither.</p>
 */
public final class FragmentResult {

    private FragmentResult() {
    }

    /** The member the fragment's address is carried in. */
    public static final String REPOSITORY_PATH = "repository_path";

    /** Every member this result's document has, and there is no second. */
    public static final List<String> MEMBERS = List.of(REPOSITORY_PATH);

    /**
     * The result one creation or change produces.
     *
     * @param repositoryPath where the fragment is
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String repositoryPath) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(REPOSITORY_PATH, new DocumentValue.Text(repositoryPath));
        return new DocumentValue.Mapping(result);
    }
}
