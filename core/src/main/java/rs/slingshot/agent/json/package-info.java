// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Every document this agent reads, and the bounds it reads them under.
 *
 * <p>A limit applied to a document that has already been collected is a limit on nothing: the
 * memory was spent before the check ran. So a refusal here happens the moment the next byte would
 * cross a bound, and no partly-built value is reachable from any path a caller can take.</p>
 *
 * <p>No bound is written down in this package. Every one of them is read through
 * {@link rs.slingshot.agent.contract.AgentContract}, which is the one place a bound is declared and
 * the one place the client and this side are compared.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.json;
