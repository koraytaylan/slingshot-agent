---
id: atomic-retention-cleanup
title: "Make Retention Cleanup Atomic and Idempotent"
workstream: "0041"
kind: task
depends_on:
  - consistent-capacity-accounting
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/MaintenanceSweep.java
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionLedger.java
  - core/src/test/java/rs/slingshot/agent/store/MaintenanceSweepTest.java
  - core/src/test/java/rs/slingshot/agent/store/SubscriptionLedgerTest.java
status: complete
merged_as: dd2b40c
---
# Make Retention Cleanup Atomic and Idempotent

Finding(s): R09 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce the artifact-survives/counters-released save interruption and duplicate subscription-end case.
2. Make deletion and reservation release one atomic transition or a durably recoverable idempotent cleanup operation.
3. Inject interruption at each persistence boundary and run overlapping/repeated cleanup with independent state and accounting checks.

- **Done when:** Every interruption and repeated cleanup leaves either retained data with its full accounting or deleted data with exactly one release; counters never become negative.

Direct implementation is complete; review loops and full-gate verification are recorded in
[EXECUTION.md](../EXECUTION.md#4104--atomic-retention-cleanup).
