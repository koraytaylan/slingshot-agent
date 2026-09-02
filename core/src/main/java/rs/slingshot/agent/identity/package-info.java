// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * What a submission means, said in full or not at all.
 *
 * <p>A command is not identified by its name. Two builds can both call something
 * {@code query_paths} and disagree about what its arguments are, what its result looks like, or how
 * large either may be — so an identity is five fields, all five match or the submission is refused,
 * and there is no sixth.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.identity;
