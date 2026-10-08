// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The two commands about the framework this agent runs inside.
 *
 * <p>Two listings, and they answer different halves of the same question. A bundle can be running
 * while the component inside it never activated, and that gap is behind most of the "it is
 * installed but the feature does not work" reports anybody files — so the component listing exists
 * beside the bundle one rather than as a detail of it.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.framework;
