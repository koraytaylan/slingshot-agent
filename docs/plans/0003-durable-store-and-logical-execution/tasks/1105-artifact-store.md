---
id: artifact-store
title: "Artifact Store"
workstream: "0011"
kind: task
depends_on:
  - filtered-replay
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/ArtifactStore.java
  - core/src/main/java/rs/slingshot/agent/store/ArtifactSlot.java
  - core/src/main/java/rs/slingshot/agent/store/ArtifactRecord.java
  - core/src/test/java/rs/slingshot/agent/store/ArtifactStoreTest.java
  - "core/src/test/resources/fixtures/artifact-store/**"
status: done
merged_as: ""
---
# Artifact Store

An answer too large for the envelope is published rather than truncated, and a reference to an artifact that does not exist is worse than no reference at all. So the bytes, the count, and the digest are committed before anything names them.

**Steps:**

1. Author fixtures for a single-slot artifact, a multi-slot one, a slot claimed twice, an artifact whose declared size and written size differ, and a store at capacity.
2. Implement `ArtifactSlot` as a claim at a path derived from the operation and the slot, so two writers cannot both take one slot.
3. Implement `ArtifactStore` to reserve capacity from the declared size before writing, stream the bytes without holding them, and write the byte count and the digest in the same commit as the content.
4. Refuse to complete an artifact whose written size differs from its declared size, release the reservation, and leave nothing reachable — a partial artifact with a correct-looking digest is the failure worth spending the most effort on.
5. Serve an artifact by path with its recorded byte count and digest, and let a reader verify without trusting anything the store says about itself.

**Tests:**

- A single-slot and a multi-slot artifact are written and read back byte-identically, with digest and count matching.
- Claiming an occupied slot is refused, including under concurrency where exactly one claim wins.
- A written size differing from the declared size refuses, releases the reservation exactly, and leaves nothing reachable.
- Capacity is reserved before the first byte, proved by a store whose remaining capacity is below the declared size and which refuses before writing.
- Streaming is proved on an artifact larger than any single buffer, with memory use independent of size asserted structurally rather than by measurement.

- **Done when:** `./mvnw verify -pl core -Dtest=ArtifactStoreTest` proves byte-identical multi-slot round trips with matching digest and count, exactly one winner for a contended slot, a size mismatch that releases capacity and leaves nothing reachable, reservation taken before the first byte, and streaming with no size-dependent buffering.
