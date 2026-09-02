// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

/**
 * Digests, and the one way two of them are compared.
 *
 * <p>Every authentication in this repository ends in a comparison of two digests, and a comparison
 * that returns on the first differing byte tells whoever supplied one of them how many bytes they
 * got right. Doing it once, here, is cheaper than auditing it at each of the places it happens —
 * and a {@link rs.slingshot.agent.digest.DigestValue} that cannot hold anything but sixty-four
 * lower-case hexadecimal characters is a digest nothing has to validate a second time.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package rs.slingshot.agent.digest;
