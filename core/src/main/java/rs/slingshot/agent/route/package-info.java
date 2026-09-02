// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Every route this agent serves, produced in one place.
 *
 * <p>No servlet writes its own path. The route table is committed under {@code policy/}, embedded
 * in this bundle as a resource, and read through
 * {@link rs.slingshot.agent.route.AgentRouteTable} — so a second spelling of a path cannot exist,
 * because there is nowhere for it to be written.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.route;
