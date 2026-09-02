---
id: wire-compatibility-snapshots
title: "Wire Compatibility Snapshots"
workstream: "0038"
kind: task
depends_on:
  - dependency-advisory-pin
gated: false
touches:
  - "compatibility/wire/**"
  - development/src/main/java/rs/slingshot/agent/development/WireCompatibility.java
  - development/src/test/java/rs/slingshot/agent/development/WireCompatibilityTest.java
  - scripts/quality
  - policy/quality-gate.toml
status: done
merged_as: ""
---
# Wire Compatibility Snapshots

A protocol change nobody noticed is a client that stops working. Snapshotting every document's canonical bytes makes the change visible at the moment it is introduced, which is the only moment it is cheap.

**Steps:**

1. Snapshot the canonical bytes of every document this agent produces and every schema it publishes, one file per document kind, under `compatibility/wire/`.
2. Snapshot the whole registry's rendered contract identity set, so a change to any command's version, limits digest, or schema digest is visible as a changed snapshot.
3. Implement `WireCompatibility` comparing the build's current output against the snapshots and failing on any difference, naming the document and the exact bytes that differ.
4. Require a deliberate recording step to change a snapshot, and require the change to carry a semantic version increment on whatever it changed, so a change is a decision rather than a regenerated file.
5. Assert the snapshot set covers every document kind and every registry row, in both directions.

**Tests:**

- Every document kind and every registry row has a snapshot, and a kind or row with none fails naming it.
- A changed document fails naming it and showing the differing bytes, proved by a fixture change to each kind.
- A snapshot cannot be updated without a semantic version increment on what it covers, and a fixture regenerating without one is rejected.
- The comparison is byte-exact rather than structural, proved by a change that is structurally equivalent and byte-different.
- Two builds of the same source produce identical current output, so a snapshot difference is always a real change.

- **Done when:** `./mvnw verify -pl development -Dtest=WireCompatibilityTest && scripts/quality` proves two-way snapshot coverage of every document kind and registry row, a byte-exact failure naming the differing bytes for a change to each kind, refusal of a regeneration without a version increment, failure on a structurally-equivalent byte-different change, and identical output across two builds.
