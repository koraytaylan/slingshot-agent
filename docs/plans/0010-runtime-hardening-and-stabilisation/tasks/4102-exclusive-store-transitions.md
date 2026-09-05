---
id: exclusive-store-transitions
title: "Establish Exclusive Oak Store Transitions"
workstream: "0041"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/CompareAndSet.java
  - core/src/main/java/rs/slingshot/agent/store/ClaimByCreation.java
  - core/src/main/java/rs/slingshot/agent/execution/OperationStore.java
  - core/src/main/java/rs/slingshot/agent/execution/SubmissionAdmission.java
  - core/src/test/java/rs/slingshot/agent/store
  - core/src/test/java/rs/slingshot/agent/execution
  - interop/src/test/java/rs/slingshot/agent/interop/harness/CrashConsistencyScenario.java
status: pending
merged_as: ""
---
# Establish Exclusive Oak Store Transitions

Finding(s): R02 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Turn the preserved identical-value CAS, identical-submission and double-start interleavings into deterministic regression tests.
2. Persist unique ownership/version identities that genuinely conflict with competing writers and validate them in the transaction that protects the change.
3. Drive the same cases on shared DocumentNodeStore nodes and count an independently observed side effect, including resend after response loss.

- **Done when:** Identical competing submissions and starts produce exactly one winner and at most one observed side effect on both embedded Oak and the shared-store runtime; losing attempts cannot return success.

Direct implementation and review evidence: [EXECUTION.md](../EXECUTION.md#4102--exclusive-store-transitions).
