---
id: reliable-terminal-finalization
title: "Preserve Truthful Terminal Outcomes"
workstream: "0043"
kind: task
depends_on:
  - resumable-request-admission
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/execution
  - core/src/main/java/rs/slingshot/agent/store/EventLedger.java
  - core/src/main/java/rs/slingshot/agent/store/SnapshotStore.java
  - core/src/main/java/rs/slingshot/agent/store/MaintenanceSweep.java
  - core/src/main/java/rs/slingshot/agent/store/StatePath.java
  - core/src/main/java/rs/slingshot/agent/wire/JobEventKind.java
  - core/src/test/java/rs/slingshot/agent/http
  - core/src/test/java/rs/slingshot/agent/execution
  - core/src/test/java/rs/slingshot/agent/store/EventLedgerTest.java
  - core/src/test/java/rs/slingshot/agent/proof/StateAccessFixtures.java
  - policy/design-patterns.toml
  - policy/repository-layout.toml
status: pending
merged_as: ""
---
# Preserve Truthful Terminal Outcomes

Finding(s): R04 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce terminal event capacity exhaustion after a handler returns, leaving status=202/RUNNING/no result.
2. Reserve finalization needs before irreversible effects where possible and handle every terminal commit outcome and typed handler failure.
3. Represent uncertain effects durably and reconcile them without blind re-execution; fault terminal persistence and response writing independently.

- **Done when:** Every executed command has a retrievable success, declared failure or explicit unknown/reconcilable outcome, and finalization failure cannot be silently acknowledged as a completed operation or cause a repeated effect.

Direct implementation is complete; see [the review and validation record](../EXECUTION.md#4302--reliable-terminal-finalization).
