// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * How this agent reaches the repository, and the two identities it reaches it as.
 *
 * <p>The agent's own bookkeeping runs as a service user granted one tree. Everything a caller asked
 * for runs as the caller, in the session their own request arrived with. There is no third path,
 * nothing impersonates, and nothing holds a credential — which is what makes the second guarantee
 * unconditional rather than carefully bounded.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.repository;
