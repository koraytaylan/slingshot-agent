// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.ConfigurationCatalogue;
import rs.slingshot.agent.command.platform.ValueDisclosure;
import rs.slingshot.agent.json.DocumentValue;

/**
 * One configuration's properties, each with the evidence that decided whether its value is here.
 *
 * <p>Absence is an answer rather than a failure. A configuration that is not there means the
 * service is running on its defaults, and that is one of the most useful things this whole surface
 * says — an operator chasing behaviour they cannot explain very often finds that the configuration
 * they were told about was never applied.</p>
 *
 * <p>Every property carries the evidence, including the ones whose value is here. An operator
 * comparing two environments needs to know that a value they can see was reported because the
 * platform described it as safe, rather than because nobody checked.</p>
 */
public final class InspectConfigurationResult {

    private InspectConfigurationResult() {
    }

    /** The member the identifier is carried in. */
    public static final String PERSISTENT_IDENTIFIER = "persistent_identifier";

    /** The member saying whether the platform holds a configuration at that identifier. */
    public static final String PRESENT = "present";

    /** The member the properties are carried in, by name. */
    public static final String PROPERTIES = "properties";

    /**
     * The member an observation states a value's own kind in.
     *
     * <p>Spelled here rather than borrowed from the value vocabulary, because the client leaves
     * the observation's shape open: it declares an object and says nothing about what is in it.
     * Borrowing the whole value's member list would claim this answer states four things the
     * client's own document never mentions.</p>
     */
    public static final String VALUE_TYPE = "type";

    /** Every member this result's document has, nested ones included. */
    public static final List<String> MEMBERS = List.of(PERSISTENT_IDENTIFIER, PRESENT, PROPERTIES,
            ValueDisclosure.METATYPE_EVIDENCE, ValueDisclosure.OBSERVATION, VALUE_TYPE);

    /**
     * The result one inspection produces.
     *
     * @param persistentIdentifier what was asked about, echoed so the answer says what it is about
     * @param inspected what the platform holds
     * @return the result document
     */
    public static DocumentValue.Mapping documentOf(String persistentIdentifier,
                                                   ConfigurationCatalogue.Inspected inspected) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(PERSISTENT_IDENTIFIER, new DocumentValue.Text(persistentIdentifier));
        result.put(PRESENT, new DocumentValue.Flag(
                inspected.present() == ConfigurationCatalogue.Presence.PRESENT
                        ? DocumentValue.Truth.TRUE : DocumentValue.Truth.FALSE));
        final SequencedMap<String, DocumentValue> properties = new LinkedHashMap<>();
        inspected.properties().forEach(property -> properties.put(property.name(),
                ValueDisclosure.documentOf(property.evidence(),
                        ValueDisclosure.of(property.evidence(), property.value()))));
        result.put(PROPERTIES, new DocumentValue.Mapping(properties));
        return new DocumentValue.Mapping(result);
    }
}
