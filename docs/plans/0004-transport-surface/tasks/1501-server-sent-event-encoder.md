---
id: server-sent-event-encoder
title: "Server-Sent Event Encoder"
workstream: "0015"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/stream/EventEncoder.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamRefusal.java
  - core/src/main/java/rs/slingshot/agent/stream/package-info.java
  - core/src/test/java/rs/slingshot/agent/stream/EventEncoderTest.java
  - "core/src/test/resources/fixtures/event-encoder/**"
status: done
merged_as: ""
---
# Server-Sent Event Encoder

An event that would exceed a bound is not truncated. A truncated event is not a smaller event but an unparseable one, and the subscriber that receives it has no way to tell which.

**Steps:**

1. Author vectors before the encoder: an event at and one past each of the three bounds, an event whose payload contains a line break, one whose identifier contains a character the format reserves, and a heartbeat.
2. Implement `EventEncoder` producing the exact wire form the client's decoder accepts, with the identifier carrying the generation and the sequence together so a cursor is never ambiguous.
3. Enforce the line, event, and buffer bounds as bytes are produced rather than after an event is built, all three read from the contract.
4. Refuse an over-bound event as a `StreamRefusal` that ends the stream, rather than emitting a partial event, and say which bound ended it.
5. Encode a heartbeat as a form the client's decoder recognises as a heartbeat and never as an event, so a heartbeat can never advance a cursor.

**Tests:**

- Each of the three bounds is proved at exactly the limit and one past it, with the refusal naming the bound.
- A payload containing a line break and an identifier containing a reserved character are each encoded so the decoder recovers the original exactly.
- No partial event is ever emitted, proved by capturing the output stream at the moment of every refusal.
- A heartbeat is proved not to advance a cursor, by decoding a stream of heartbeats and asserting the cursor unchanged.
- The identifier is proved to carry both generation and sequence, and a fixture carrying only a sequence is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=EventEncoderTest` proves both sides of all three bounds with the bound named, exact recovery of line breaks and reserved characters, no partial emission at any refusal, a heartbeat that cannot advance a cursor, and an identifier carrying generation and sequence together.
