// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * What twenty commands that change a repository have in common.
 *
 * <p>Reading is forgiving: a read that gets something wrong tells somebody the wrong thing and they
 * usually notice. A write that gets something wrong changes a repository other people depend on,
 * and the interesting cases are the ones where it half worked. So the decisions those twenty share
 * are made once, here, rather than twenty times.</p>
 *
 * <h2>The third answer</h2>
 *
 * <p>A commit interrupted after the write and before the acknowledgement leaves a state this side
 * cannot determine. That is neither success nor failure and it is not rare enough to ignore, so it
 * is a third answer a caller can be given — and a caller who receives it looks rather than assumes.
 * Reporting it as a failure would be telling somebody something false about their own
 * repository.</p>
 *
 * <h2>Guards are arguments</h2>
 *
 * <p>A guard this side chose is a guard the caller did not. Whether to delete something other
 * things reference, whether a move adjusts the references that point at it, where a reordered
 * component ends up — each is a required argument with no default, because each is right sometimes
 * and this side cannot tell which time it is.</p>
 *
 * <p>Which guards exist is the client's to say and not this plan's. Two the plan's own design named
 * — a removal budget and an expected prior state — are not members of any command the client
 * publishes, and they are not invented here: a guard the other half never sends is a guard nobody
 * chose, which is the thing this package exists to prevent.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.mutation;
