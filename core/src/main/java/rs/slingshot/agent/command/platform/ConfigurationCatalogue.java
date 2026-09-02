// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;
import java.util.SequencedMap;

/**
 * What answers questions about the platform's configurations, and changes them.
 *
 * <p>A seam for the same reason the replication one is: what is on the other side of it is a
 * platform service, and everything worth arguing about is on this side. Which properties may be
 * reported is decided by {@link ValueDisclosure} here; what a deployment permits is decided by
 * {@link PlatformControl} here; how much one answer may carry is decided by the contract here. What
 * crosses is the reading and the writing.</p>
 *
 * <p>Reading a property's value and deciding whether to report it are separate calls on purpose. An
 * implementation that returned values and left the deciding to the caller would have already read
 * every password on the instance into this process's memory, and the only thing standing between
 * that and a log line would be a caller who remembered.</p>
 */
public interface ConfigurationCatalogue {

    /**
     * One configuration as a listing names it.
     *
     * @param persistentIdentifier what the platform calls it
     * @param factoryPersistentIdentifier the factory it came from, or {@link #NOT_FROM_A_FACTORY}
     * @param propertyKeyCount how many properties it has, which is a count and never the values
     * @param binding whether it is bound to one bundle's location
     */
    record Entry(String persistentIdentifier, String factoryPersistentIdentifier,
                 long propertyKeyCount, Binding binding) {
    }

    /** What a listing says when a configuration came from no factory. */
    String NOT_FROM_A_FACTORY = "";

    /** Whether a configuration is tied to one bundle's location. */
    enum Binding {
        /** It is, so only that bundle receives it. */
        BOUND_TO_A_BUNDLE_LOCATION,
        /** It is not. */
        UNBOUND
    }

    /**
     * What one property is, with its own name.
     *
     * <p>Named rather than keyed, and held in a list rather than a map, because the order a
     * configuration's properties come back in is part of the answer: two environments compared
     * property by property are much easier to read when the two lists line up.</p>
     *
     * @param name the property's own name
     * @param evidence what the Meta Type Service says about it
     * @param value what it holds, which the caller may only report where the evidence permits
     */
    record Property(String name, ValueDisclosure.Evidence evidence, ConfigurationValue value) {
    }

    /** What reading or changing a configuration produced. */
    sealed interface Outcome permits Listed, Inspected, Changed, Failed {
    }

    /**
     * The configurations a search found.
     *
     * @param entries what it found, in the platform's own order
     */
    record Listed(List<Entry> entries) implements Outcome {

        /** Holds the entries apart from whatever produced them. */
        public Listed {
            entries = List.copyOf(entries);
        }
    }

    /**
     * One configuration's properties.
     *
     * @param present whether the platform holds one at that identifier at all
     * @param properties its properties, in the platform's own order, each with the evidence that
     *     decides disclosure
     */
    record Inspected(Presence present, List<Property> properties) implements Outcome {

        /** Holds the properties apart from whatever produced them. */
        public Inspected {
            properties = List.copyOf(properties);
        }
    }

    /** Whether the platform holds a configuration at one identifier. */
    enum Presence {
        /** It does. */
        PRESENT,
        /**
         * It does not.
         *
         * <p>Which is an answer rather than a failure. Asking about a configuration that is not
         * there is how an operator finds out a service is running on its defaults, and that is one
         * of the most useful things this whole surface says.</p>
         */
        ABSENT
    }

    /**
     * A change the platform made.
     *
     * @param changedPropertyKeyCount how many property keys the change touched
     * @param origin whether what was changed came from a factory
     */
    record Changed(long changedPropertyKeyCount, Origin origin) implements Outcome {
    }

    /** Whether a configuration came from a factory, which decides what removing it means. */
    enum Origin {
        /** It is a factory instance, so removing it removes that instance and nothing else. */
        FACTORY_INSTANCE,
        /** It is a singleton configuration. */
        SINGLETON
    }

    /**
     * The platform would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said, carrying no configuration value
     */
    record Failed(String category, String detail) implements Outcome {
    }

    /**
     * The configurations whose identifier begins with one prefix.
     *
     * @param prefix what the identifier begins with, which is empty for every configuration
     * @param budget the most this may examine before it refuses
     * @return what it found, or the reason there is nothing
     */
    Outcome find(String prefix, long budget);

    /**
     * One configuration's properties, with the evidence that decides what may be reported.
     *
     * @param persistentIdentifier what the platform calls it
     * @return its properties, or the reason there are none
     */
    Outcome inspect(String persistentIdentifier);

    /**
     * Writes a configuration, setting what is named and removing what is listed.
     *
     * <p>Named for what it does rather than with a verb the tooling reads as a mutator prefix. A
     * seam whose methods are called {@code update} and {@code delete} is a seam every static
     * analyser treats as a mutable object being handed around, and it is not one — it is a
     * stateless view onto a platform service.</p>
     *
     * @param persistentIdentifier what the platform calls it
     * @param assignments what to set, by property name
     * @param removedPropertyKeys what to remove
     * @return what changed, or the reason nothing did
     */
    Outcome apply(String persistentIdentifier, SequencedMap<String, ConfigurationValue> assignments,
                   List<String> removedPropertyKeys);

    /**
     * Removes a configuration.
     *
     * @param persistentIdentifier what the platform calls it
     * @return what was removed, or the reason nothing was
     */
    Outcome erase(String persistentIdentifier);
}
