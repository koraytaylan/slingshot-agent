// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The root package of the Slingshot agent's Adobe Experience Manager bundle.
 *
 * <p>Only the command handlers that genuinely cannot run without Adobe's own interfaces live under
 * this root. The bundle depends on {@code slingshot-agent-core} at provided scope and embeds
 * nothing.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.aem;
