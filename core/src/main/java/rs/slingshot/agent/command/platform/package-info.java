// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * The seams the Adobe-only bundle implements, and the only package this bundle exports.
 *
 * <p>Everything else here is private to the bundle. What is exported is exactly the set of
 * interfaces the other half plugs into — no argument type, no handler, no result — because an
 * exported package is a promise, and a promise about a hundred classes is a promise nobody can
 * keep. The interfaces are narrow on purpose: each one is what a platform service does, stated
 * without any of the deciding that surrounds it, so the deciding stays where it can be proved on
 * plain Apache Sling.</p>
 *
 * <p>Exported without a matching import. A bundle that both exports and imports a package can have
 * the framework substitute somebody else's copy of it, which for a seam this bundle defines and
 * implements the calling side of would mean two incompatible copies of one interface in one
 * runtime — the failure that produces is a class cast nobody can read.</p>
 */
@org.osgi.annotation.bundle.Export(
        substitution = org.osgi.annotation.bundle.Export.Substitution.NOIMPORT)
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.command.platform;
