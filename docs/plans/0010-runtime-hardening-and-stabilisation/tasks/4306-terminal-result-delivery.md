---
id: terminal-result-delivery
title: "Expose Durable Command Results Through the Contract"
workstream: "0043"
kind: task
depends_on:
  - reliable-terminal-finalization
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/SubmissionResponse.java
  - core/src/main/java/rs/slingshot/agent/http/OperationLookupServlet.java
  - core/src/main/java/rs/slingshot/agent/stream
  - core/src/main/java/rs/slingshot/agent/wire
  - core/src/main/java/rs/slingshot/agent/execution/TerminalCommit.java
  - schemas/agent-protocol
  - core/src/test/resources/fixtures/agent-contract/sibling-transport-contract.json
  - core/src/test/java/rs/slingshot/agent/http
  - core/src/test/java/rs/slingshot/agent/wire
status: complete
merged_as: "d7b92ab"
---
# Expose Durable Command Results Through the Contract

Finding(s): R05 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Map the durable terminal result and failure types to the sibling's authoritative transport envelopes and identify any required coordinated schema/digest correction.
2. Use the stored result in the actual response/recovery path, including the committed descriptor for overflow artifacts.
3. Execute commands producing known inline bytes, a declared failure and overflow bytes; lose the initial response and recover through the same published routes.

- **Done when:** A client following the committed transport contract can recover the exact inline result or actual downloadable artifact descriptor, and the precise terminal failure, after losing the original response.
