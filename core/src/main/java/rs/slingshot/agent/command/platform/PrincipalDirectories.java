// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.platform;

/**
 * Where one run gets its own view of the repository's users and groups.
 *
 * <p>Held by a handler rather than the directory itself, for the same reason a run opens its own
 * staging room rather than sharing one. A directory is something a run has; a handler that owned
 * one would be a handler through which every run shared whatever the implementation kept.</p>
 */
@FunctionalInterface
public interface PrincipalDirectories {

    /**
     * A view for one run.
     *
     * @return the directory
     */
    PrincipalDirectory open();
}
