---
id: heartbeat-and-session-bound
title: "Heartbeat and Session Bound"
workstream: "0015"
kind: task
depends_on:
  - event-stream-route
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/stream/Heartbeat.java
  - core/src/main/java/rs/slingshot/agent/stream/SessionBound.java
  - docs/DEPLOYMENT.md
  - core/src/test/java/rs/slingshot/agent/stream/HeartbeatTest.java
  - core/src/test/java/rs/slingshot/agent/stream/DefaultStreamTickerTest.java
status: done
merged_as: ""
---
# Heartbeat and Session Bound

A stream that has stopped sending heartbeats is a stream that has stopped, which is different from a stream that has nothing to say. And a stream a gateway severs at an interval nobody declared is a stream that ends at a moment nobody chose — so this side ends its own first, inside a bound it publishes.

**Steps:**

1. Author fixtures for a heartbeat at the declared interval, a session ended at the declared bound, a session ended by the client, and a bound configured above the client's resumability relation.
2. Implement `Heartbeat` writing at the contract's interval from the bundle's own bounded executor — never the platform's shared scheduler, which exists for periodic work rather than for holding one entry per open stream, and which every other feature on the instance is also using — whether or not there is anything to say.
3. Implement `SessionBound` to close the stream cleanly at the declared maximum session duration, after a final heartbeat, so the client's decoder sees an ordinary end rather than a severed connection.
4. Assert the relation Plan 0001 established: the session bound is strictly below the heartbeat timeout multiplied by the client's retry attempt count, so a session ending on schedule is always resumable inside the client's own policy.
5. Document in `docs/DEPLOYMENT.md` the gateway and load-balancer idle timeouts this bound must stay under, per deployment row, so an operator with a shorter one knows to say so — and state plainly the thing a row cannot claim until it has been observed: that the row's own ingress passes a `text/event-stream` response through without buffering it. A buffered stream is not a slow stream, it is a stream that delivers nothing until it ends, and no amount of correct behaviour on this side changes that. Until a tier has watched events arrive on a row, the row's streaming support is declared and unproved like anything else.

**Tests:**

- Heartbeats arrive at the declared interval, proved against the contract value on a monotonic clock, with nothing sleeping for a fixed span.
- A session ends at exactly the declared bound and not before, proved at the bound and one interval short of it.
- The ending is a clean close following a final heartbeat, asserted from the received bytes.
- The resumability relation is asserted from the contract at build time, and a fixture contract violating it fails.
- Heartbeats are proved to be written from the bundle's own executor and not from the platform's shared scheduler, asserted over the module, and the executor is proved bounded by the same stream concurrency bound rather than growing with subscribers.
- The documented ingress requirement is asserted present for every deployment row, and a row claiming streaming support with no observation behind it is rejected.
- On a running instance, a stream held past the session bound is resumed by a reconnection carrying the last identifier with no event lost and none repeated.

- **Done when:** `./mvnw verify -pl core -Dtest=HeartbeatTest && ./mvnw verify -pl interop -Dtest=SessionBoundScenario` proves contract-interval heartbeats on a monotonic clock, a session ending exactly at its bound with a clean close after a final heartbeat, a build-time resumability assertion, and a running-instance resumption across the bound with nothing lost or repeated.
