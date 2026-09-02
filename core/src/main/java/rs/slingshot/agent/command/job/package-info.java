// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The four commands about the work the platform is holding for later.
 *
 * <p>Jobs are where an author instance's problems go to hide. Replication that is not happening,
 * workflows that never started, indexes that were never rebuilt — all of it is a queue with
 * something stuck in it, and none of it is visible from the content. So these four exist to answer
 * the question "what is not happening", which is much harder to ask than "what happened".</p>
 *
 * <p>No job property value crosses this surface, in any command, ever. A job's properties belong to
 * whatever created it and routinely carry content addresses and occasionally a credential. What an
 * operator needs is which topic, in which queue, in which state, tried how many times — and, for
 * one stuck job, the <em>names</em> of what it carries, which is enough to say what kind of work it
 * is.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.job;
