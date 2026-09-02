// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Discovery: what this agent tells a client it is, before the client sends it anything.
 *
 * <p>It is the first route this product serves because its answer was fully specified by the client
 * that calls it before this side existed. Answering it honestly with an empty command list is a
 * more useful skeleton than answering a health check nobody consumes.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.discovery;
