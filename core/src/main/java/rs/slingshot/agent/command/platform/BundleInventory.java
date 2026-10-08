// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;

/**
 * What answers questions about the framework's bundles and components.
 *
 * <p>One seam for two commands because they are two questions about one framework, and an
 * implementation that answered one of them from the framework and one from somewhere else would be
 * answering about two different instants.</p>
 */
public interface BundleInventory {

    /**
     * One bundle as a listing names it.
     *
     * @param bundleIdentifier the framework's own number for it
     * @param symbolicName what it is called
     * @param version which version of it this is
     * @param state what state it is in
     */
    record BundleEntry(long bundleIdentifier, String symbolicName, String version,
                       BundleState state) {
    }

    /**
     * One component as a listing names it.
     *
     * @param name the component's own name
     * @param bundleSymbolicName the bundle that declares it
     * @param servicePersistentIdentifier the configuration it takes, or {@link #TAKES_NO_SERVICE}
     * @param state what state it is in
     */
    record ComponentEntry(String name, String bundleSymbolicName,
                          String servicePersistentIdentifier, ComponentState state) {
    }

    /** What a listing says when a component takes no configuration of its own. */
    String TAKES_NO_SERVICE = "";

    /** What one of the two produced. */
    sealed interface Outcome permits Bundles, Components, Refused {
    }

    /**
     * The bundles a listing found.
     *
     * @param entries what it found, in the framework's own order
     */
    record Bundles(List<BundleEntry> entries) implements Outcome {

        /** Holds the entries apart from whatever produced them. */
        public Bundles {
            entries = List.copyOf(entries);
        }
    }

    /**
     * The components a listing found.
     *
     * @param entries what it found, in the framework's own order
     */
    record Components(List<ComponentEntry> entries) implements Outcome {

        /** Holds the entries apart from whatever produced them. */
        public Components {
            entries = List.copyOf(entries);
        }
    }

    /**
     * The framework would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said
     */
    record Refused(String category, String detail) implements Outcome {
    }

    /**
     * The bundles whose symbolic name begins with one prefix and whose state is one of a set.
     *
     * @param prefix what a symbolic name begins with, which is empty for every bundle
     * @param states which states to include, which is empty for every state
     * @return what it found, or the reason there is nothing
     */
    Outcome bundles(String prefix, List<BundleState> states);

    /**
     * The components whose name begins with one prefix and whose state is one of a set.
     *
     * @param prefix what a name begins with, which is empty for every component
     * @param states which states to include, which is empty for every state
     * @return what it found, or the reason there is nothing
     */
    Outcome components(String prefix, List<ComponentState> states);
}
