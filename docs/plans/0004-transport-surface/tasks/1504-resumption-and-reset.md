---
id: resumption-and-reset
title: "Resumption and Reset"
workstream: "0015"
kind: task
depends_on:
  - heartbeat-and-session-bound
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/stream/StreamResumption.java
  - core/src/main/java/rs/slingshot/agent/stream/ResetNotice.java
  - core/src/test/java/rs/slingshot/agent/stream/StreamResumptionTest.java
status: done
merged_as: ""
---
# Resumption and Reset

A subscriber that jumped silently would believe it had seen everything. So a cursor the store can no longer honour produces a reset that says so and carries what to resynchronise from — never an empty result and never a different position.

**Steps:**

1. Author fixtures for a resumption from a live cursor, from a swept cursor, from a cursor past the newest event, from a foreign generation, and with no cursor at all.
2. Implement `StreamResumption` reading the resumption identifier as a generation and a sequence together, so a cursor from an earlier incarnation is recognised rather than misread as an early position.
3. Serve strictly after the cursor, in sequence order, never re-delivering the cursor's own event.
4. Implement `ResetNotice` as an event the client's decoder recognises, carrying the snapshot to resynchronise from, emitted whenever the cursor cannot be honoured.
5. Serve a cursorless reconnection the snapshot and the events after it, which exposes nothing that was already exposed and retracts nothing that was.

**Tests:**

- Resumption from a live cursor yields exactly the events after it and never the cursor's own.
- A swept cursor yields a reset carrying the snapshot; a cursor past the newest yields nothing and no reset; the two are distinct.
- A foreign-generation cursor yields a reset naming the current generation rather than being read as an early position.
- A cursorless reconnection exposes nothing at or below the snapshot's sequence.
- On a running instance, a subscriber disconnected mid-stream and reconnected with its last identifier receives every event once and no event twice, across a store that swept in between.

- **Done when:** `./mvnw verify -pl core -Dtest=StreamResumptionTest && ./mvnw verify -pl interop -Dtest=StreamResetScenario` proves strictly-after resumption, distinct reset and empty-forward outcomes, a foreign-generation cursor resolved as a reset, a cursorless reconnection exposing nothing already exposed, and exactly-once delivery across a real disconnection and an intervening sweep.
