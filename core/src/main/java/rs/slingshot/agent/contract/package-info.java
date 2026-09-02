// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The bounds this agent is held to, and the one accessor that reads them.
 *
 * <p>Every limit and formula this product obeys is declared once, in {@code agent-contract.toml},
 * and reached through {@link rs.slingshot.agent.contract.ContractLimit}. Half of them are the
 * client's own transport contract reproduced byte-equivalently; the other half are this side's,
 * because they are properties of the server's environment rather than of the protocol.</p>
 *
 * <p>Nothing outside this package writes one of those values down a second time. A limit written
 * down twice is two things that can disagree quietly, for as long as nobody compares them, which is
 * a defect the sibling repository shipped once before the rule existed.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.contract;
