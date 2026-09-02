// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.health;

import java.util.List;

/**
 * Whether the tree this agent writes into exists and is protected the way it was declared.
 *
 * <p>The tree is created by a deployment's own initialisation script rather than by this bundle, so
 * a mismatch between what the script made and what this build expects is not something either side
 * notices on its own. It shows up later as a write that fails for one caller, or — much worse — as
 * a read that succeeds for one who should not have been able to make it.</p>
 *
 * <p>The first difference is named rather than counted. "Three entries differ" sends an operator to
 * compare two lists by hand; "everyone is granted jcr:read and the declaration does not grant it"
 * is the whole answer, and it is the one that says which way the mistake runs.</p>
 */
public final class StateTreeHealthCheck {

    private StateTreeHealthCheck() {
    }

    /** Whether the tree is there at all. */
    public enum Presence {
        /** It is. */
        PRESENT,
        /** It is not, which is the deployment's initialisation script never having run. */
        ABSENT
    }

    /**
     * Whether the tree is there and protected as declared.
     *
     * @param root where the tree sits
     * @param presence whether it is there at all
     * @param declared the access-control entries the layout declares, as the repository spells them
     * @param present the entries the repository actually holds
     * @return one result an operator can act on, naming the first difference rather than a count
     */
    public static AgentHealth.Result of(String root, Presence presence, List<String> declared,
                                        List<String> present) {
        if (presence == Presence.ABSENT) {
            return AgentHealth.unhealthy(AgentHealth.Check.STATE_TREE, root + " does not exist. It"
                    + " is created by this deployment's own repository initialisation, not by this"
                    + " bundle, so nothing here can make it: run the initialisation that ships"
                    + " with this package.");
        }
        final String missing = firstAbsent(declared, present);
        if (!missing.isEmpty()) {
            return AgentHealth.unhealthy(AgentHealth.Check.STATE_TREE, root + " is missing the"
                    + " declared access-control entry " + missing + ". A node with no entry is a"
                    + " node whose writes fail for the one caller who needed them, at the moment"
                    + " they needed them.");
        }
        final String unexpected = firstAbsent(present, declared);
        if (!unexpected.isEmpty()) {
            return AgentHealth.unhealthy(AgentHealth.Check.STATE_TREE, root + " carries the"
                    + " access-control entry " + unexpected + ", which the layout does not"
                    + " declare. An entry nobody declared is a reach nobody decided to grant.");
        }
        return AgentHealth.healthy(AgentHealth.Check.STATE_TREE, root + " exists and carries"
                + " exactly the " + declared.size() + " declared access-control entries");
    }

    /**
     * The first of one list that the other does not hold.
     *
     * @param wanted what to look for
     * @param found what is there
     * @return the first one missing, or the empty string where none is
     */
    private static String firstAbsent(List<String> wanted, List<String> found) {
        return wanted.stream()
                .filter(entry -> !found.contains(entry))
                .findFirst()
                .orElse("");
    }
}
