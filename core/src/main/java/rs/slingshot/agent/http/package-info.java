// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * How a request reaches a route, and every way it is refused before it reaches anything else.
 *
 * <p>Sling will hand a path-bound servlet a request that arrived with a selector, an extension, a
 * suffix, or a trailing segment. Each of those is a second spelling of a route, and a route with
 * spellings nobody enumerated is a route whose policy applies to some of the ways it can be
 * reached. Everything here exists to make the exact path the only way in.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.http;
