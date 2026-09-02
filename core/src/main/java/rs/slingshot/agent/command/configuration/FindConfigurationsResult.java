// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.json.DocumentValue;

/**
 * Which configurations exist, and how large each one is, and never what any of them holds.
 *
 * <p>A count of property keys rather than the keys, and certainly not the values. A search is the
 * one call somebody makes across a whole instance, and its output is the one that ends up pasted
 * into a ticket — so what it carries has to be safe to paste. The count is what an operator
 * actually needs from a listing: it tells them which configuration is the one somebody has
 * customised, and they can then ask about that one.</p>
 *
 * <p>Whether a configuration is bound to a bundle location is carried because it changes what the
 * configuration means: a bound one is delivered to exactly one bundle, so a service that looks
 * misconfigured may simply be receiving a different configuration than the one being read.</p>
 */
public final class FindConfigurationsResult {

    private FindConfigurationsResult() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member one match's identifier is carried in. */
    public static final String PERSISTENT_IDENTIFIER = "persistent_identifier";

    /** The member the factory an instance came from is carried in, where it came from one. */
    public static final String FACTORY_PERSISTENT_IDENTIFIER = "factory_persistent_identifier";

    /** The member the count of property keys is carried in. */
    public static final String PROPERTY_KEY_COUNT = "property_key_count";

    /** The member saying whether a configuration is tied to one bundle's location. */
    public static final String BOUND_TO_A_BUNDLE_LOCATION = "bound_to_a_bundle_location";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(BOUND_TO_A_BUNDLE_LOCATION,
            FACTORY_PERSISTENT_IDENTIFIER, MATCHES, NEXT_CONTINUATION_TOKEN, PERSISTENT_IDENTIFIER,
            PROPERTY_KEY_COUNT);

    /** What the token member says when this is the last page. */
    public static final String NO_MORE_PAGES = "";

    /**
     * The result one search produces.
     *
     * @param entries what it found, in the platform's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(List<ConfigurationCatalogue.Entry> entries,
                                                   String nextContinuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(entries.stream()
                .map(FindConfigurationsResult::entryOf)
                .toList()));
        if (!NO_MORE_PAGES.equals(nextContinuationToken)) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(nextContinuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue entryOf(ConfigurationCatalogue.Entry entry) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(PERSISTENT_IDENTIFIER, new DocumentValue.Text(entry.persistentIdentifier()));
        if (!ConfigurationCatalogue.NOT_FROM_A_FACTORY.equals(
                entry.factoryPersistentIdentifier())) {
            match.put(FACTORY_PERSISTENT_IDENTIFIER,
                    new DocumentValue.Text(entry.factoryPersistentIdentifier()));
        }
        match.put(PROPERTY_KEY_COUNT, new DocumentValue.Whole(entry.propertyKeyCount()));
        match.put(BOUND_TO_A_BUNDLE_LOCATION, new DocumentValue.Flag(
                entry.binding() == ConfigurationCatalogue.Binding.BOUND_TO_A_BUNDLE_LOCATION
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(match);
    }
}
