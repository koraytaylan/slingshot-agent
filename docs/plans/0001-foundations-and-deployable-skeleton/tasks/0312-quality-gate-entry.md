---
id: quality-gate-entry
title: "One Gate That Takes No Argument"
workstream: "0003"
kind: task
depends_on:
  - source-policy-checker
  - nullability-contract
  - api-shape-and-naming
  - method-shape-and-early-exit
  - documentation-completeness
  - allocation-and-stream-discipline
  - adobe-practice-policy
  - coverage-floor
  - content-package-analysis-gate
  - dependency-policy-and-locked-cache
  - dual-licence-and-source-headers
  - publication-metadata-boundary
gated: false
touches:
  - scripts/quality
  - policy/quality-gate.toml
  - development/src/main/java/rs/slingshot/agent/development/QualityGate.java
  - development/src/test/java/rs/slingshot/agent/development/QualityGateTest.java
  - "development/src/test/resources/fixtures/quality-gate/**"
status: done
merged_as: ""
---
# One Gate That Takes No Argument

A gate with options is a gate people run with different options. This one has none, so what a contributor ran and what continuous integration ran are the same thing by construction.

**Steps:**

1. Author fixtures for the exact ordered stage list and for a gate that omits one stage.
2. Write `scripts/quality` taking no argument, running the stages in a declared order: cache verification, formatting, compilation with warnings as errors, the static-analysis set, the source policy, the six code-doctrine policies — nullability, API shape, method shape, documentation, allocation, and Adobe practice — the tests with the coverage floor, the package analysis, the dependency policy, the imported-package footprint, the module direction, and the public interop tier.
3. Write `policy/quality-gate.toml` as the stage inventory, and check the script against it so a stage that exists and is not run, or is run and is not declared, fails.
4. Make the script fetch nothing and write nothing into the repository, and prove both rather than asserting them: run it with no reachable network and compare the working tree before and after. What the gate needs prepared — the locked dependency cache and the pinned interop images — it verifies rather than acquires, refusing with the preparation command named when either is absent, because a gate that quietly fetched a missing input would be a gate whose result depended on a remote server after all.
5. Keep the tiers that need a licensed input out of it entirely, and have the script say at the end which tiers it did not run and which command runs each, so nobody mistakes a passing gate for a complete one.

**Tests:**

- The stage list is asserted exactly and in order against the inventory, and a fixture omitting a stage or adding an undeclared one is rejected.
- The script is asserted to accept no argument, refusing any it is given rather than ignoring it.
- A run over a clean tree leaves it byte-identical, compared file by file including modification of any generated report location.
- A run with no reachable network completes with the cache and the images prepared, proving no stage fetches.
- A run with either the cache or an image absent refuses naming the preparation command rather than acquiring it, two distinct refusals with no network access attempted.
- The closing report names every tier not run and the exact command for each, checked against the tier inventory rather than written out separately.

- **Done when:** `scripts/quality && ./mvnw verify -pl development -Dtest=QualityGateTest` passes, proving the exact ordered stage set, argument refusal, an unchanged working tree, a run with no network against prepared inputs, two distinct refusals naming the preparation command when an input is absent, and a closing report that names every unrun tier from the inventory.
