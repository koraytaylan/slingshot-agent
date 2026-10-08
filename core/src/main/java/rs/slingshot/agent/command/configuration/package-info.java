// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The two commands about the platform's own configuration.
 *
 * <p>Both of them read, and nothing here writes. Reading works everywhere: an environment whose
 * configuration is immutable still answers questions about it, and on such an environment that is
 * <em>most</em> of what an operator wants — they cannot change it, so knowing exactly what it says
 * is the entire job.</p>
 *
 * <p>No listing here ever carries a configuration value. A search answers how many properties a
 * configuration has and never what they are, because a search is the one call somebody makes across
 * a whole instance and the one whose output ends up in a paste.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.configuration;
