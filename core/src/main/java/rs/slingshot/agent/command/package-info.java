// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * What a command is, before any command exists.
 *
 * <p>Sixty-four commands is a lot of surface, and the way it goes wrong is not that one of them is
 * written badly. It is that the fortieth is written slightly differently from the first — obtains
 * its own session, spends an unbounded traversal, decides its own result limit, invents a failure
 * category — and each of those looks reasonable on its own. So the machinery comes first, and it is
 * built so those things are not available to do.</p>
 *
 * <p>One file per command rather than one shared list. A shared list is a file every command task
 * has to edit, which turns a footprint rule into a queue and makes sixty independent pieces of work
 * into one sequence.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command;
