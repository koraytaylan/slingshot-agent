// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What was removed, and whether it was one instance of a factory or the configuration itself.
 *
 * <p>The distinction is carried because it changes what just happened. Removing a factory instance
 * removes one of several configurations a service holds and leaves the others; removing a singleton
 * returns that service to its defaults entirely. An operator who thought they were doing the first
 * and did the second has changed the behaviour of everything on the instance.</p>
 */
public final class DeleteConfigurationResult {

    private DeleteConfigurationResult() {
    }

    /** The member the identifier is carried in. */
    public static final String PERSISTENT_IDENTIFIER = "persistent_identifier";

    /** The member saying whether what was removed was one instance of a factory. */
    public static final String WAS_A_FACTORY_INSTANCE = "was_a_factory_instance";

    /** Every member this result's document has, and there is no third. */
    public static final List<String> MEMBERS =
            List.of(PERSISTENT_IDENTIFIER, WAS_A_FACTORY_INSTANCE);

    /**
     * The result one removal produces.
     *
     * @param persistentIdentifier what was removed
     * @param origin whether it came from a factory
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String persistentIdentifier,
                                                   ConfigurationCatalogue.Origin origin) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(PERSISTENT_IDENTIFIER, new DocumentValue.Text(persistentIdentifier));
        result.put(WAS_A_FACTORY_INSTANCE, new DocumentValue.Flag(
                origin == ConfigurationCatalogue.Origin.FACTORY_INSTANCE
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        return new DocumentValue.Mapping(result);
    }
}
