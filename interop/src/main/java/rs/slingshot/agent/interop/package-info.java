// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The root package of the Slingshot agent's interoperability harness.
 *
 * <p>The harness starts container tiers rootlessly through a process wrapper this repository owns,
 * installs the container package into a real Sling runtime, and observes what the installed agent
 * answers. It produces no installable artifact.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.interop;
