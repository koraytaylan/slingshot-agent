---
id: maintenance-sweep
title: "Maintenance Sweep"
workstream: "0012"
kind: task
depends_on:
  - retention-policy
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/MaintenanceSweep.java
  - core/src/main/java/rs/slingshot/agent/store/SweepCursor.java
  - core/src/main/java/rs/slingshot/agent/store/SweepReport.java
  - "ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/**"
  - core/src/test/java/rs/slingshot/agent/store/MaintenanceSweepTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/MaintenanceSweepScenario.java
  - interop/scenarios/maintenance-sweep.toml
status: done
merged_as: ""
---
# Maintenance Sweep

A sweep that runs to completion or not at all is a sweep that never finishes on a full store. This one is bounded, resumable, and deterministic, and it removes only what retention already permits removing.

**Steps:**

1. Author fixtures for a sweep with nothing to do, one bounded by its work limit, one resumed from a cursor, one interrupted mid-pass, and one over a store containing an unreferenced artifact.
2. Implement `SweepCursor` as a durable position so a sweep resumes where it stopped rather than restarting, and so two sweeps cannot both work the same region.
3. Implement `MaintenanceSweep` to remove records whose retained-until has passed, release their capacity exactly, and stop at the declared work bound with the cursor advanced.
4. Collect unreferenced artifacts — the ones an interrupted terminal commit leaves behind — and prove that collecting one never removes a referenced artifact, however the reference was written. Unreferenced is not enough on its own: bytes written and not yet named are exactly what a worker mid-terminal-commit has just produced, so an artifact is collectable only when its operation holds no live lease, when it is older than the contract's lease duration, and when no live manifest still declares its slot. Sweeping a moment early is how a sweep becomes the thing that breaks an answer.
5. Produce a `SweepReport` that is deterministic for a given store state, so two runs over the same store produce identical bytes and a difference is a real change.

**Tests:**

- A sweep with nothing to do removes nothing and advances the cursor to the end.
- A sweep stops at exactly its work bound with the cursor advanced, and a resumed sweep covers exactly the remainder with no overlap and no gap.
- An interrupted sweep leaves the store consistent, proved by the snapshot-and-ledger verification pass afterwards.
- An unreferenced artifact is collected and a referenced one is not, across every way a reference is written.
- An unreferenced artifact whose operation holds a live lease, one younger than the lease duration, and one whose slot a live manifest still declares are each left alone, proved at exactly the lease duration and one interval past it.
- Capacity after a sweep equals the store's actual contents, and two runs over one store produce byte-identical reports.

- **Done when:** `./mvnw verify -pl core -Dtest=MaintenanceSweepTest && ./mvnw verify -pl interop -Dtest=MaintenanceSweepScenario` proves a bounded pass with an advanced cursor and gapless resumption, a consistent store after interruption, unreferenced-only collection across every reference form with a live lease, a young artifact, and a declared slot each left alone, exact capacity release, and byte-identical reports across two runs.
