// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The six commands about workflows: what models exist, what is running, and what to do about it.
 *
 * <p>One rule runs through all of them and it is the reason this package needs care. A workflow is
 * work the platform carries out <em>afterwards</em>, under its own identity, on content the caller
 * named. That makes starting one the most powerful thing on this whole surface: a caller who cannot
 * change a page but can start a workflow whose steps change it has changed it. So a workflow is
 * started only on a payload the caller could have changed themselves, checked through their own
 * session before the platform is asked.</p>
 *
 * <p>The three that read carry no workflow variable. A workflow's variables belong to whatever
 * created it and routinely hold content, addresses, and occasionally a token; what an operator
 * needs is which model is running, on what, in what state, and who it is waiting for.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.workflow;
