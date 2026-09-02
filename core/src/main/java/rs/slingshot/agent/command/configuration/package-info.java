// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The four commands about the platform's own configuration.
 *
 * <p>Two of them read and two of them write, and the difference between those pairs is the whole
 * shape of this package. Reading works everywhere: an environment whose configuration is immutable
 * still answers questions about it, and on such an environment that is <em>most</em> of what an
 * operator wants — they cannot change it, so knowing exactly what it says is the entire job.
 * Writing is refused there before it touches the platform, because a write that is accepted and
 * then discarded by the next release is worse than no write at all.</p>
 *
 * <p>No listing here ever carries a configuration value. A search answers how many properties a
 * configuration has and never what they are, because a search is the one call somebody makes across
 * a whole instance and the one whose output ends up in a paste.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.configuration;
