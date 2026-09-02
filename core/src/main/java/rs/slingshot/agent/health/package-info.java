// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * What this agent publishes into the author's own operations dashboard.
 *
 * <p>Six checks rather than one, because a single aggregate tells an operator only that something
 * is wrong. The point of publishing readiness at all is that somebody finds it without being told
 * to look, and what they find has to be actionable the moment they read it.</p>
 *
 * <p>Two of the six exist because of how this fails on somebody else's instance rather than on
 * ours, and both are silent. A path-bound servlet registers only for prefixes the servlet resolver
 * permits, so a deployment that has narrowed them has an agent that is installed, active, and
 * unreachable — which looks like nothing at all. And a query is only cheap while the index covering
 * it exists, which is a property of the customer's repository rather than of this build. Neither is
 * visible anywhere else, and both are the operator's to fix.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.health;
