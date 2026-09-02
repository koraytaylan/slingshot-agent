// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * One long-lived response, and everything that keeps it from becoming somebody else's problem.
 *
 * <p>A synchronous long-lived response holds a request thread, and an author serves from a bounded
 * pool of them: a handful of subscribers on a synchronous route is an author that has stopped
 * serving anything at all. So a stream here releases its request thread, writes from this bundle's
 * own bounded executor rather than from the platform's shared scheduler, ends itself at a bound it
 * publishes rather than waiting for a gateway to sever it, and is admitted against a count of how
 * many streams this instance is already holding.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.stream;
