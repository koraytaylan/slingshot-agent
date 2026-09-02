// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The runtimes a scenario can be run against.
 *
 * <p>They differ in what they prove rather than in how thoroughly they run. The public tier needs
 * nothing licensed and proves everything that does not touch an Adobe interface; the quickstart tier
 * needs a jar its holder is licensed for and proves the rest; the third is the second driven by the
 * client's own executable, and is the only one that proves the two halves of the protocol speak to
 * one another.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.interop.tier;
