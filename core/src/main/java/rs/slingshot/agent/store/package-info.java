// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Where this agent keeps what it knows, and how it finds it again.
 *
 * <p>Every lookup is a path derived from an identifier, and never a query. A query needs an index,
 * an index has to be current, and "the index had not caught up yet" is an idempotency answer that
 * is wrong rather than slow — the same submission would be admitted twice because the first one was
 * not visible yet.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.store;
