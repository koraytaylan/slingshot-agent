// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.store;

import rs.slingshot.agent.digest.DigestValue;

/**
 * What was published into one slot: how many bytes, what they digest to, and when.
 *
 * <p>The count and the digest are the record rather than a description of it. A reader that has the
 * bytes can decide for itself whether they are the bytes this record is about, which is the only
 * arrangement where a truncated transfer is detectable by the side that would be harmed by it.</p>
 *
 * @param slot which slot
 * @param byteCount how many bytes were written
 * @param digest what those bytes digest to
 * @param publishedAtUnixMilliseconds when the commit that made them reachable happened
 */
public record ArtifactRecord(ArtifactSlot slot, long byteCount, DigestValue digest,
                             long publishedAtUnixMilliseconds) {
}
