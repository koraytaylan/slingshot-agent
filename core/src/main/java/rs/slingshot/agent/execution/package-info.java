// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * One durable thing per submission, and what may happen to it.
 *
 * <p>The record and the acceptance are the same act. Accepting first and recording afterwards
 * leaves a window in which the client has been told yes and this side has forgotten — and the
 * client's whole recovery story is that a request which produced no answer prompts a lookup rather
 * than a resend.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.execution;
