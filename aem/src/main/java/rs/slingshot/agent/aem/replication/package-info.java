// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The one place this build talks to Adobe's replication service.
 *
 * <p>Everything about <em>which</em> items are offered is decided in the Sling-only bundle: who may
 * read them, how far the walk goes, which bounds refuse. What is here is the handing over, and it
 * is here because {@code com.day.cq.replication} exists only on an Adobe runtime. Keeping the
 * decision on one side of that line and the call on the other is what lets the whole command be
 * proved on plain Apache Sling.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.aem.replication;
