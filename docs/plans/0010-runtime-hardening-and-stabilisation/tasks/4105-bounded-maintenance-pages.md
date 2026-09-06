---
id: bounded-maintenance-pages
title: "Enforce Maintenance Bounds Inside Buckets"
workstream: "0041"
kind: task
depends_on:
  - atomic-retention-cleanup
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/MaintenanceSweep.java
  - core/src/main/java/rs/slingshot/agent/store/SweepCursor.java
  - core/src/test/java/rs/slingshot/agent/store/MaintenanceSweepTest.java
status: pending
merged_as: ""
---
# Enforce Maintenance Bounds Inside Buckets

Finding(s): R14 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Seed multiple valid operation identifiers in the same bucket and count node iteration as well as examined records.
2. Persist a cursor that resumes within a bucket and check work bounds before advancing/retaining each record.
3. Prove successive bounded passes eventually cover the eligible set without missing or double-releasing entries.

- **Done when:** Each sweep respects its declared read/work bound even in one dense bucket, and repeated passes collect the entire eligible set with correct accounting.

Direct implementation is in progress; regression evidence and review loops are recorded in
[EXECUTION.md](../EXECUTION.md#4105--bounded-maintenance-pages-in-progress).
