---
id: event-stream-route
title: "Event Stream Route"
workstream: "0015"
kind: task
depends_on:
  - server-sent-event-encoder
  - failure-status-mapping
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/EventStreamServlet.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamSession.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamHandoff.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamWriter.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamExecutor.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamTicker.java
  - core/src/main/java/rs/slingshot/agent/stream/DefaultStreamTicker.java
  - core/src/test/java/rs/slingshot/agent/http/EventStreamServletTest.java
  - "core/src/test/resources/fixtures/event-stream/**"
  - interop/src/test/java/rs/slingshot/agent/interop/tier/EventStreamScenario.java
  - interop/scenarios/event-stream.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Event Stream Route

A synchronous long-lived response holds a request thread, and an Adobe Experience Manager as a Cloud Service author serves from a bounded pool of them. A handful of subscribers on a synchronous route is an author that has stopped serving anything at all.

**Steps:**

1. Author fixtures for a stream with events waiting, one with none, one whose subscription is unknown, one on a foreign generation, and one whose caller does not own the operation.
2. Implement `EventStreamServlet` to start an asynchronous context and release the request thread before waiting, writing only from the bundle's own bounded executor when there is something to write — not from the platform's shared scheduler, because a per-stream entry on a pool the rest of the instance depends on is this agent spending somebody else's capacity.
3. Implement `StreamSession` holding the subscription, the cursor, and the generation, filtered to one operation, so no event for another operation can be reached however the request is shaped.
4. Refuse an unknown subscription, a foreign generation, and an unowned operation before the stream opens, as ordinary responses rather than as stream errors, since a client that never got a stream should not have to parse one to find out why.
5. Release every resource on any ending — completion, client disconnection, session bound, or error — through one path, so no ending leaks a subscription record or a scheduler entry.

**Tests:**

- The request thread is proved released before the first wait, asserted structurally rather than by measurement.
- Events waiting are delivered in sequence order; a stream with none delivers heartbeats and no events.
- The three pre-stream refusals are ordinary responses with their categories, and none opens a stream.
- No request shape reaches another operation's events, proved across a crafted corpus.
- Every ending releases through one path, proved by asserting no residual subscription record or executor entry after each of the four endings, and the executor is proved to return to idle.

- **Done when:** `./mvnw verify -pl core -Dtest=EventStreamServletTest && ./mvnw verify -pl interop -Dtest=EventStreamScenario` proves the thread released before waiting, ordered delivery and heartbeat-only quiet streams, three pre-stream refusals that open no stream, no cross-operation reachability across a crafted corpus, and no residual state after any of the four endings.
