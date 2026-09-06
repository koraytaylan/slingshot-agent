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
  - core/src/test/java/rs/slingshot/agent/store/SaveInterleaving.java
  - core/src/test/java/rs/slingshot/agent/proof/ExclusiveTransitionProbe.java
  - core/src/test/java/rs/slingshot/agent/proof/IntakePublicationProbe.java
  - interop/src/main/java/rs/slingshot/agent/interop/harness/CrashInjector.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/TierRequests.java
  - interop/src/test/java/rs/slingshot/agent/interop/harness
  - interop/scenarios/intake-publication-crash.toml
  - policy/design-patterns.toml
status: complete
merged_as: e0d4a99
---
# Validate Intake Before Publishing a Completed Slot

Finding(s): R07 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Add a mismatched-digest upload with an observer and an injected crash at the existing publish/delete boundary.
2. Keep incoming bytes unreachable as a completed artifact until expected size and digest are validated, coupling publication with reservation completion.
3. Test mismatch, truncation, retry, concurrent completion and process death using independent readers.

- **Done when:** No reader or retry can observe invalid or partial bytes as a completed intake slot at any persistence boundary, and rejected uploads leave the correct reusable reservation.

Direct implementation is complete; see [the review and validation record](../EXECUTION.md#4303--atomic-intake-publication).
