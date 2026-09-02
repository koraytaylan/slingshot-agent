// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Every repository-policy check, and the toolkit they are all built on.
 *
 * <p>Each check in this package reads the same four things: a declarative policy document, the
 * resolved build model, a produced artifact, and a report somebody has to be able to read. Writing
 * that four times, slightly differently each time, is how a repository ends up with checks that
 * disagree about what a finding is, so it is written once here.</p>
 *
 * <p>Nothing in this module reaches a running container, a repository, or a network. A check reads
 * committed files, a resolved build model, and a produced archive, and it needs no running
 * anything — which is the property that keeps the gate runnable on somebody's laptop.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.development;
