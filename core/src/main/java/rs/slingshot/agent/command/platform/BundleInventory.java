// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.List;

/**
 * What answers questions about the framework's bundles and components, and changes their state.
 *
 * <p>One seam for three commands because they are three questions about one framework, and an
 * implementation that answered two of them from the framework and one from somewhere else would be
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

    /** What one of the three produced. */
    sealed interface Outcome permits Bundles, Components, Transitioned, Refused {
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
     * What state a bundle ended up in.
     *
     * <p>Reported rather than assumed. A bundle asked to start may end up resolved because one of
     * its components would not activate, and a command that answered "started" because the request
     * did not throw would be telling an operator the opposite of what they need to know.</p>
     *
     * @param observed what state it is in now
     */
    record Transitioned(BundleState observed) implements Outcome {
    }

    /**
     * The framework would not, or could not.
     *
     * @param category the declared category this is reported under
     * @param detail what it said
     */
    record Refused(String category, String detail) implements Outcome {
    }

    /** What to do to a bundle. */
    enum Transition {
        /** Start it. */
        START("start"),
        /** Stop it. */
        STOP("stop"),
        /** Refresh it, which restarts everything wired to it. */
        REFRESH("refresh");

        private final String spelling;

        Transition(String spelling) {
            this.spelling = spelling;
        }

        /**
         * How the wire spells this transition.
         *
         * @return the spelling
         */
        public String spelling() {
            return spelling;
        }

        /**
         * The transition one spelling names.
         *
         * @param spelled what was written
         * @return the transition, or nothing where nothing is spelled that way
         */
        public static java.util.Optional<Transition> named(String spelled) {
            return java.util.Arrays.stream(values())
                    .filter(transition -> transition.spelling.equals(spelled))
                    .findFirst();
        }

        /**
         * Every transition, spelled as the wire spells it.
         *
         * @return the spellings, in declaration order
         */
        public static List<String> spellings() {
            return java.util.Arrays.stream(values()).map(Transition::spelling).toList();
        }
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

    /**
     * Puts one bundle through a transition and reports where it ended up.
     *
     * @param symbolicName which bundle
     * @param transition what to do to it
     * @return the state it is in now, or the reason nothing happened
     */
    Outcome transition(String symbolicName, Transition transition);
}
