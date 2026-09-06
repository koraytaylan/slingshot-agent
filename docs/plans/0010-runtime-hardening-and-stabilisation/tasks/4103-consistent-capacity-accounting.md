---
id: consistent-capacity-accounting
title: "Make Capacity Reservations and Releases Consistent"
workstream: "0041"
kind: task
depends_on:
  - exclusive-store-transitions
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/CapacityLedger.java
  - core/src/main/java/rs/slingshot/agent/store/ShardedCount.java
  - core/src/main/java/rs/slingshot/agent/store/LedgerAdmission.java
  - core/src/main/java/rs/slingshot/agent/stream/StreamAdmission.java
  - core/src/test/java/rs/slingshot/agent/store/CapacityLedgerTest.java
  - core/src/test/java/rs/slingshot/agent/stream
status: complete
merged_as: b655509
---
# Make Capacity Reservations and Releases Consistent

Finding(s): R08; R02 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce the two-release total=0/share=1 race and interrupted admission/release with independently refreshed sessions.
2. Give reservations idempotent identities and commit total/caller changes together, handling every contention outcome from fresh state.
3. Reconcile abandoned reservations after process death and validate quota equality against the live reservations rather than clamping counters.

- **Done when:** Concurrent, repeated and interrupted admission/release preserves exact total and caller accounting and restores all room after work ends or recovery runs, without exceeding configured bounds.

Direct implementation is complete; the review loops, final gate result, and scope boundaries are
recorded in [EXECUTION.md](../EXECUTION.md#4103--consistent-capacity-accounting).
