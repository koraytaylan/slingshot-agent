// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command;

import java.util.Optional;

/**
 * Where a command that needs somewhere to work asks for it, once per run.
 *
 * <p>A handler is a long-lived thing and a staging area is not: it is opened, written into, and
 * taken away again, and the run that did so is over. A handler holding one would be a handler that
 * works once, and the second run would find a room somebody else already closed. So the handler
 * holds the way to ask instead, and the room it is given belongs to that run alone.</p>
 *
 * <p>Asking can fail to produce anything — a row declaring no room, a tree the agent cannot write
 * in — and that is an empty answer rather than an exception, because a command with nowhere to work
 * has a declared category for exactly that and reporting it is what the caller needs.</p>
 */
@FunctionalInterface
public interface StagingRooms {

    /**
     * Opens the room for one run, which that run closes however it ends.
     *
     * @return the room, or nothing where none could be opened
     */
    Optional<StagingArea> open();
}
