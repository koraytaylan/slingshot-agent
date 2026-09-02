---
id: filtered-replay
title: "Filtered Replay and Reset"
workstream: "0011"
kind: task
depends_on:
  - subscription-ledger
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/EventReplay.java
  - core/src/main/java/rs/slingshot/agent/store/ReplayCursor.java
  - core/src/main/java/rs/slingshot/agent/store/ReplayOutcome.java
  - core/src/test/java/rs/slingshot/agent/store/EventReplayTest.java
  - "core/src/test/resources/fixtures/event-replay/**"
status: done
merged_as: ""
---
# Filtered Replay and Reset

A reconnection with no cursor exposes nothing that was already exposed and retracts nothing that was. A reconnection with a cursor the store can no longer honour has to say so, because silently starting somewhere else is a subscriber that believes it saw everything.

**Steps:**

1. Author fixtures for replay from a valid cursor, from a cursor before the earliest retained event, from a cursor past the newest, from a foreign generation, and with no cursor at all.
2. Implement `ReplayCursor` as a generation and a sequence together, so a cursor from an earlier incarnation is recognised rather than misread as an early position.
3. Implement `EventReplay` to serve events strictly after the cursor, in sequence order, bounded per read by the contract's limits.
4. Make a cursor the store can no longer honour a `ReplayOutcome` that says so and carries the snapshot to resynchronise from — a reset — rather than a silent jump or an empty result.
5. Filter to one operation and one subscriber, and prove no event for another operation can be reached through a subscription, whatever cursor is supplied.

**Tests:**

- Replay from a valid cursor yields exactly the events after it in order, and never the cursor's own event.
- A cursor before the earliest retained event yields a reset carrying the snapshot, distinctly from a cursor past the newest, which yields nothing and no reset.
- A cursor from another generation is refused distinctly rather than treated as an early position.
- A cursorless replay yields the snapshot and the events after it, and is proved to expose nothing at or below the snapshot's sequence.
- No cursor value reaches an event belonging to another operation, proved across a corpus of crafted cursors.

- **Done when:** `./mvnw verify -pl core -Dtest=EventReplayTest` proves strictly-after ordered replay, a reset carrying the snapshot distinct from an empty forward read, a distinctly refused foreign-generation cursor, a cursorless read exposing nothing already exposed, and no crafted cursor reaching another operation's events.
