// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Resuming a query where it left off, without trusting whoever asks.
 *
 * <p>A token says "resume where you were", and two things follow. It has to carry enough to name
 * that place exactly, because resuming somewhere else is worse than starting over. And it has to be
 * unforgeable, because the place it names is inside somebody's data.</p>
 *
 * <p>The query a token belongs to is part of what is signed. That is a real attack rather than a
 * hypothetical one: a position in one result set is a perfectly plausible position in another, and
 * a token that only said "position four hundred" would happily resume the wrong search.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.continuation;
