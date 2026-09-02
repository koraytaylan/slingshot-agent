// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The three commands about the framework this agent runs inside.
 *
 * <p>Two listings and one control, and the two listings answer different halves of the same
 * question. A bundle can be running while the component inside it never activated, and that gap is
 * behind most of the "it is installed but the feature does not work" reports anybody files — so the
 * component listing exists beside the bundle one rather than as a detail of it.</p>
 *
 * <p>The control is refused outright on a deployment whose bundle state comes from the deployed
 * image. Stopping a bundle there lasts until the next container replaces it, which is a change with
 * a lifetime nobody can predict and an answer nobody should act on.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.framework;
