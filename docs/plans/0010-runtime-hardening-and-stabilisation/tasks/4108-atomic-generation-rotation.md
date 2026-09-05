---
id: atomic-generation-rotation
title: "Publish Generation Rotation Atomically"
workstream: "0041"
kind: task
depends_on:
  - exclusive-store-transitions
  - consistent-capacity-accounting
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/GenerationStore.java
  - core/src/main/java/rs/slingshot/agent/store/GenerationRotation.java
  - core/src/main/java/rs/slingshot/agent/store/RetainedGeneration.java
  - core/src/test/java/rs/slingshot/agent/store/GenerationRotationTest.java
  - core/src/test/java/rs/slingshot/agent/store/GenerationStoreTest.java
status: pending
merged_as: ""
---
# Publish Generation Rotation Atomically

Finding(s): R09 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce serving=2/history=[1] with generation 1 immediately retired after an interrupted save.
2. Publish serving generation, history and required retention metadata in one fenced atomic or durably recoverable transition.
3. Test every save boundary and competing rotations; read retained operation/snapshot/artifact state after restart.

- **Done when:** An interrupted or competing rotation never publishes a serving generation without matching history and retention, and previously retained work remains readable until its actual retention deadline.
