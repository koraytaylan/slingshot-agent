// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The commands that read content and replace nothing.
 *
 * <p>They live in the Sling-only bundle rather than beside the Adobe ones, because reading a
 * repository subtree needs the repository and nothing Adobe adds to it. Putting them where the
 * Adobe interfaces are would mean the public tier — the one that runs on any machine with nothing
 * licensed — could not prove a single one of them, which is the whole reason the two-bundle split
 * exists.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.content;
