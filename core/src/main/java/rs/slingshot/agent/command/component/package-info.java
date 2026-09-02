// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The commands that add, change, remove and reorder the components inside a page.
 *
 * <p>A component is what an author actually moves around, so these are the mutations whose ordering
 * matters. Order is stated as a neighbour rather than an index, because an index is a race with
 * whoever else has the page open: the third slot when the request was written is the fourth by the
 * time it arrives, and the component lands somewhere nobody asked for.</p>
 *
 * <p>A parent that cannot hold an order is reported as itself rather than as a generic refusal. It
 * is a fact about the repository the author is working in, and one they can do something about.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.component;
