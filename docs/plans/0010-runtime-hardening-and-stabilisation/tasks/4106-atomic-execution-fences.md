---
id: atomic-execution-fences
title: "Persist Execution Fence Authority Atomically"
workstream: "0041"
kind: task
depends_on:
  - exclusive-store-transitions
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/ExecutionFence.java
  - core/src/main/java/rs/slingshot/agent/execution/FenceHolder.java
  - core/src/test/java/rs/slingshot/agent/execution/ExecutionFenceTest.java
status: complete
merged_as: 037ff6d
---
# Persist Execution Fence Authority Atomically

Finding(s): R10 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Inject failure between expiry and owner persistence and reproduce the unrecoverable partial fence.
2. Persist owner, expiry and unique acquisition epoch together and require that epoch for renewal and protected transitions.
3. Add conservative recovery of incomplete historical records and stale-holder takeover tests without enabling deferred execution.

- **Done when:** A crash at any acquisition/renewal boundary leaves a valid owned fence or a recoverable expired/incomplete fence, and an old owner cannot modify work after a new epoch wins.

Direct implementation is complete; review loops and full-gate verification are recorded in
[EXECUTION.md](../EXECUTION.md#4106--atomic-execution-fences).
