---
id: interop-workflow
title: "Interop Workflow"
workstream: "0039"
kind: task
depends_on:
  - quality-workflow
gated: false
touches:
  - .github/workflows/interop.yml
  - development/src/test/java/rs/slingshot/agent/development/InteropWorkflowTest.java
  - docs/INTEROP.md
status: done
merged_as: ""
---
# Interop Workflow

The public tier runs on every change because it needs nothing licensed. The other tiers cannot, and saying which ones did not run is more useful than a green badge that means less than it looks like it means.

**Steps:**

1. Write `.github/workflows/interop.yml` running the public tier rootlessly on the hosted runner, under the same workflow policy the quality workflow is held to, preparing the pinned images and the locked dependency cache in their own declared steps before the gate runs so the gate itself still fetches nothing.
2. Report at the end which tiers did not run and the exact command for each, read from the tier inventory rather than written into the workflow.
3. Fail the workflow on a container the harness left behind, so a leaked container is a build failure rather than a slow accumulation on somebody's runner.
4. Publish the tier's captured output as an artifact bounded by the harness's own capture bound, so a failure is diagnosable without a rerun.
5. Keep the licensed tiers out entirely, with the workflow asserting it did not attempt one rather than skipping quietly.

**Tests:**

- The workflow is held to the same policy as the quality workflow, using the very same check.
- The preparation steps run before the gate and are the only steps that reach the network, asserted over the workflow's parsed structure.
- The unrun tiers named at the end equal the tier inventory minus the public tier, in both directions.
- A leaked container fails the workflow, proved against a fixture harness that leaks one.
- The published output is bounded by the harness capture bound, and an unbounded publication is rejected.
- The workflow is asserted to attempt no licensed tier, over its parsed structure.

- **Done when:** `./mvnw verify -pl development -Dtest=InteropWorkflowTest` proves the interop workflow held to the same policy check as the quality workflow, unrun tiers named from the inventory in both directions, a leaked container failing the run, bounded output publication, and no licensed tier attempted.
