// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The commands that make, change, move and remove a page.
 *
 * <p>A page is the thing an author works in, so these are the mutations that get used most and the
 * ones whose half-way states are noticed by the most people. Each is one commit or none, each states
 * its guards as arguments rather than choosing them, and each answers with what changed rather than
 * with a yes — an address a caller can compare against the one they asked for catches a class of
 * defect that no boolean can.</p>
 *
 * <p>They are built on the vocabulary in the mutation package rather than each carrying its own, so
 * the fourth of them behaves like the first.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.page;
