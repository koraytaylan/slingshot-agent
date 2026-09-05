---
id: atomic-intake-publication
title: "Validate Intake Before Publishing a Completed Slot"
workstream: "0043"
kind: task
depends_on:
  - consistent-capacity-accounting
  - state-access-and-ownership
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/IntakeSlotWrite.java
  - core/src/main/java/rs/slingshot/agent/store/ArtifactStore.java
  - core/src/test/java/rs/slingshot/agent/http/ArtifactIntakeServletTest.java
  - core/src/test/java/rs/slingshot/agent/store/ArtifactStoreTest.java
status: pending
merged_as: ""
---
# Validate Intake Before Publishing a Completed Slot

Finding(s): R07 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Add a mismatched-digest upload with an observer and an injected crash at the existing publish/delete boundary.
2. Keep incoming bytes unreachable as a completed artifact until expected size and digest are validated, coupling publication with reservation completion.
3. Test mismatch, truncation, retry, concurrent completion and process death using independent readers.

- **Done when:** No reader or retry can observe invalid or partial bytes as a completed intake slot at any persistence boundary, and rejected uploads leave the correct reusable reservation.
