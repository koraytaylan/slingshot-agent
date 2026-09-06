---
id: durable-stream-progress
title: "Record Subscription Progress from Actual Delivery"
workstream: "0043"
kind: task
depends_on:
  - resumable-request-admission
  - atomic-generation-rotation
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/stream/StreamWriter.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamSession.java
  - core/src/main/java/rs/slingshot/agent/store/HighWaterMark.java
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionLedger.java
  - core/src/main/java/rs/slingshot/agent/http/HighWaterServlet.java
  - core/src/test/java/rs/slingshot/agent/http/EventStreamServletTest.java
  - core/src/test/java/rs/slingshot/agent/http/HighWaterServletTest.java
  - core/src/test/java/rs/slingshot/agent/stream
status: complete
merged_as: "d9fd958"
---
# Record Subscription Progress from Actual Delivery

Finding(s): R06 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce a successful terminal stream whose durable cursor remains NOTHING_SHOWN_YET.
2. Apply the authoritative delivery/acknowledgement semantics and commit cursor/activity together, including initial snapshot and reset handling.
3. Test disconnect before/after flush and state commit, reconnection, retention and terminal close using a separate repository session for assertions.

- **Done when:** Successful delivered/acknowledged events advance the correct owned durable cursor according to the transport contract, and reconnect/expiry behavior remains correct at every flush/commit ambiguity boundary.
