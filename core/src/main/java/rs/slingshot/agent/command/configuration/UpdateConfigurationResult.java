// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.json.DocumentValue;

/**
 * How many property keys the change touched, and nothing about what any of them now holds.
 *
 * <p>A count rather than the resulting configuration, and that is not laziness. Answering with what
 * the configuration now says would mean reading every value back — including the passwords the
 * inspection command carefully refuses to report — through a command nobody would think to look at
 * when they were auditing what discloses secrets.</p>
 */
public final class UpdateConfigurationResult {

    private UpdateConfigurationResult() {
    }

    /** The member the identifier is carried in. */
    public static final String PERSISTENT_IDENTIFIER = "persistent_identifier";

    /** The member the count of touched property keys is carried in. */
    public static final String CHANGED_PROPERTY_KEY_COUNT = "changed_property_key_count";

    /** Every member this result's document has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(CHANGED_PROPERTY_KEY_COUNT, PERSISTENT_IDENTIFIER);

    /**
     * The result one change produces.
     *
     * @param persistentIdentifier what was changed
     * @param changedPropertyKeyCount how many property keys it touched
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String persistentIdentifier,
                                                   long changedPropertyKeyCount) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(PERSISTENT_IDENTIFIER, new DocumentValue.Text(persistentIdentifier));
        result.put(CHANGED_PROPERTY_KEY_COUNT, new DocumentValue.Whole(changedPropertyKeyCount));
        return new DocumentValue.Mapping(result);
    }
}
