// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.framework;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import rs.slingshot.agent.command.platform.BundleInventory;
import rs.slingshot.agent.json.DocumentValue;

/**
 * What the three framework commands answer.
 *
 * <p>Nothing here carries a configuration value, a service property, or anything else a bundle
 * holds. What a bundle is called, which version it is, and what state it is in are facts about the
 * deployment; what it has been configured with is a different question with a different command and
 * a different disclosure rule, and merging the two would route around that rule.</p>
 */
public final class FrameworkResults {

    private FrameworkResults() {
    }

    /** The member the matches are carried in. */
    public static final String MATCHES = "matches";

    /** The member the token reaching the next page is carried in, where there is one. */
    public static final String NEXT_CONTINUATION_TOKEN = "next_continuation_token";

    /** The member a bundle's own framework number is carried in. */
    public static final String BUNDLE_IDENTIFIER = "bundle_identifier";

    /** The member a bundle's symbolic name is carried in. */
    public static final String SYMBOLIC_NAME = "symbolic_name";

    /** The member a bundle's version is carried in. */
    public static final String VERSION = "version";

    /** The member a state is carried in. */
    public static final String STATE = "state";

    /** The member a component's own name is carried in. */
    public static final String NAME = "name";

    /** The member the bundle declaring a component is carried in. */
    public static final String BUNDLE_SYMBOLIC_NAME = "bundle_symbolic_name";

    /** The member the configuration a component takes is carried in, where it takes one. */
    public static final String SERVICE_PERSISTENT_IDENTIFIER = "service_persistent_identifier";

    /** The member the state a bundle ended up in is carried in. */
    public static final String OBSERVED_STATE = "observed_state";

    /** Every member a bundle listing has. */
    public static final List<String> BUNDLE_MEMBERS = List.of(BUNDLE_IDENTIFIER, MATCHES,
            NEXT_CONTINUATION_TOKEN, STATE, SYMBOLIC_NAME, VERSION);

    /** Every member a component listing has. */
    public static final List<String> COMPONENT_MEMBERS = List.of(BUNDLE_SYMBOLIC_NAME, MATCHES,
            NAME, NEXT_CONTINUATION_TOKEN, SERVICE_PERSISTENT_IDENTIFIER, STATE);

    /** Every member a transition's answer has. */
    public static final List<String> TRANSITION_MEMBERS = List.of(OBSERVED_STATE, SYMBOLIC_NAME);

    /** What the token member says when this is the last page. */
    public static final String NO_MORE_PAGES = "";

    /**
     * The result one bundle listing produces.
     *
     * @param entries what it found, in the framework's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping bundlesOf(List<BundleInventory.BundleEntry> entries,
                                                  String nextContinuationToken) {
        return paged(entries.stream().map(FrameworkResults::bundleOf).toList(),
                nextContinuationToken);
    }

    /**
     * The result one component listing produces.
     *
     * @param entries what it found, in the framework's own order
     * @param nextContinuationToken the token reaching the next page, or {@link #NO_MORE_PAGES}
     * @return the result document
     */
    public static DocumentValue.Mapping componentsOf(
            List<BundleInventory.ComponentEntry> entries, String nextContinuationToken) {
        return paged(entries.stream().map(FrameworkResults::componentOf).toList(),
                nextContinuationToken);
    }

    /**
     * The result one transition produces.
     *
     * @param symbolicName which bundle it was
     * @param observed what state it is in now, which is reported rather than assumed
     * @return the result document
     */
    public static DocumentValue.Mapping transitionOf(String symbolicName,
                                                     rs.slingshot.agent.command.platform.BundleState
                                                             observed) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(SYMBOLIC_NAME, new DocumentValue.Text(symbolicName));
        result.put(OBSERVED_STATE, new DocumentValue.Text(observed.spelling()));
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue.Mapping paged(List<DocumentValue> matches,
                                               String nextContinuationToken) {
        final SequencedMap<String, DocumentValue> result = new LinkedHashMap<>();
        result.put(MATCHES, new DocumentValue.Sequence(matches));
        if (!NO_MORE_PAGES.equals(nextContinuationToken)) {
            result.put(NEXT_CONTINUATION_TOKEN, new DocumentValue.Text(nextContinuationToken));
        }
        return new DocumentValue.Mapping(result);
    }

    private static DocumentValue bundleOf(BundleInventory.BundleEntry entry) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(BUNDLE_IDENTIFIER, new DocumentValue.Whole(entry.bundleIdentifier()));
        match.put(SYMBOLIC_NAME, new DocumentValue.Text(entry.symbolicName()));
        match.put(VERSION, new DocumentValue.Text(entry.version()));
        match.put(STATE, new DocumentValue.Text(entry.state().spelling()));
        return new DocumentValue.Mapping(match);
    }

    private static DocumentValue componentOf(BundleInventory.ComponentEntry entry) {
        final SequencedMap<String, DocumentValue> match = new LinkedHashMap<>();
        match.put(NAME, new DocumentValue.Text(entry.name()));
        match.put(BUNDLE_SYMBOLIC_NAME, new DocumentValue.Text(entry.bundleSymbolicName()));
        if (!BundleInventory.TAKES_NO_SERVICE.equals(entry.servicePersistentIdentifier())) {
            match.put(SERVICE_PERSISTENT_IDENTIFIER,
                    new DocumentValue.Text(entry.servicePersistentIdentifier()));
        }
        match.put(STATE, new DocumentValue.Text(entry.state().spelling()));
        return new DocumentValue.Mapping(match);
    }
}
