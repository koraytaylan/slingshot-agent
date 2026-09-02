---
id: snapshot-materialisation
title: "Snapshot Materialisation"
workstream: "0011"
kind: task
depends_on:
  - event-ledger
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/SnapshotStore.java
  - core/src/test/java/rs/slingshot/agent/store/SnapshotStoreTest.java
  - "core/src/test/resources/fixtures/snapshot-store/**"
status: done
merged_as: ""
---
# Snapshot Materialisation

A disconnected stream is incomplete unless something can be asked what is currently true. The snapshot is written in the same commit as the event that changed it, so the two cannot disagree and there is no job whose lateness could make them.

**Steps:**

1. Author fixtures for a snapshot after each event kind, for a snapshot read concurrently with an append, and for a store whose snapshot and ledger have been made to disagree by hand.
2. Implement `SnapshotStore` so that writing an event and updating the snapshot is one commit, with no path that writes either alone.
3. Make the agreement checkable rather than assumed: a verification pass folds the ledger and compares it with the stored snapshot, and is used by the maintenance sweep and by every test that appends. The pass also compares the operation record's own state against the snapshot's kind in both directions, so a terminal record with no terminal event, and a terminal event with a non-terminal record, are both findings that name the operation.
4. Serve a reader with no cursor the snapshot and the events after it, which exposes nothing already exposed and retracts nothing that was.
5. Refuse a snapshot write that is not accompanied by its event, structurally, so the invariant cannot be broken by a caller that means well.

**Tests:**

- After each event kind, the snapshot equals the fold of the ledger, checked by the verification pass.
- A reader observing during an append sees either the pre-append pair or the post-append pair, never a mixed one.
- A hand-made disagreement is detected by the verification pass and named, including a record made terminal without its event and an event made terminal without its record.
- No path writes a snapshot without its event, asserted over the type's surface.
- A cursorless reader is served a snapshot and the events after it, and is proved to be shown nothing with a sequence at or below the snapshot's.

- **Done when:** `./mvnw verify -pl core -Dtest=SnapshotStoreTest` proves snapshot-equals-fold after every event kind, no mixed observation during an append, detection of a hand-made disagreement in either direction including a terminal record without its event, no snapshot-only write path, and a cursorless read exposing nothing at or below the snapshot sequence.
