// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The kinds of platform control there are, and there is no seventh.
 *
 * <p>Grouped by what a deployment either provides or does not, rather than one capability per
 * command. A deployment that keeps its configuration immutable keeps all of it immutable; there is
 * no environment where updating a configuration works and deleting one does not. Splitting them
 * would give an operator six places to get the same answer wrong.</p>
 */
public enum ControlCapability {

    /** Changing or removing a platform configuration. */
    CONFIGURATION_CHANGE("configuration_change"),

    /** Starting or stopping a bundle. */
    BUNDLE_LIFECYCLE("bundle_lifecycle"),

    /** Starting, ending, or suspending a workflow instance. */
    WORKFLOW_CONTROL("workflow_control"),

    /** Cancelling work the platform is holding for later. */
    JOB_CONTROL("job_control"),

    /** Making, changing, or removing a user or a group. */
    PRINCIPAL_ADMINISTRATION("principal_administration"),

    /** Acting on a replication queue. */
    REPLICATION_CONTROL("replication_control");

    private final String spelling;

    ControlCapability(String spelling) {
        this.spelling = spelling;
    }

    /**
     * How a deployment row spells this capability.
     *
     * @return the spelling
     */
    public String spelling() {
        return spelling;
    }

    /**
     * The capability one spelling names.
     *
     * @param spelled what a row said
     * @return the capability, or nothing where nothing is spelled that way
     */
    public static Optional<ControlCapability> named(String spelled) {
        return Arrays.stream(values())
                .filter(capability -> capability.spelling.equals(spelled))
                .findFirst();
    }

    /**
     * Every capability, spelled as a deployment row spells it.
     *
     * @return the spellings, in declaration order
     */
    public static List<String> spellings() {
        return Arrays.stream(values()).map(ControlCapability::spelling).toList();
    }
}
