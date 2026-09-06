---
id: enforceable-transfer-deadlines
title: "Enforce Transfer Deadlines During Blocked I/O"
workstream: "0043"
kind: task
depends_on:
  - durable-stream-progress
  - atomic-intake-publication
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/ArtifactServlet.java
  - core/src/main/java/rs/slingshot/agent/http/BoundedRequestBody.java
  - core/src/main/java/rs/slingshot/agent/http/TransferDeadlines.java
  - core/src/main/java/rs/slingshot/agent/http/EventStreamServlet.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamHandoff.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamExecutor.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamWriter.java
  - core/src/test/java/rs/slingshot/agent/http
  - interop/src/test/java/rs/slingshot/agent/interop/tier/TransportDisruptionScenario.java
status: complete
merged_as: 3763d6d
---
# Enforce Transfer Deadlines During Blocked I/O

Finding(s): R17 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Instrument request/response I/O that stops returning and determine the actual runtime-supported cancellation and timeout mechanism.
2. Use monotonic duration bounds that can end stalled reads/writes and give resources back across initial flush, async handoff, worker rejection, disconnect and shutdown.
3. Run slow/nonreading peer cases on the installed runtime and inspect worker, session and reservation counts after each deadline.

- **Done when:** A stalled upload, download or stream ends within its published applicable deadline and releases held workers, sessions and reservations, including failures before writer handoff.
