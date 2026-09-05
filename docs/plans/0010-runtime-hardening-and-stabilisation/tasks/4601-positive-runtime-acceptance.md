---
id: positive-runtime-acceptance
title: "Require Successful Runtime Behavior in Acceptance Tests"
workstream: "0046"
kind: task
depends_on:
  - deterministic-subscription-time
  - console-runtime-assembly
  - enforceable-transfer-deadlines
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/tier/
  - interop/src/main/java/rs/slingshot/agent/interop/harness
  - interop/scenarios
  - development/src/main/java/rs/slingshot/agent/development
  - development/src/test
  - policy/quality-gate.toml
  - support/acceptance-matrix.toml
  - scripts/quality
status: pending
merged_as: ""
---
# Require Successful Runtime Behavior in Acceptance Tests

Finding(s): R16; all repaired guarantees in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Require positive installed-bundle submissions, actual results/content changes, paging, artifact bytes, stream/cursor progress and populated console before a feature scenario passes.
2. Apply redaction and disruption cases to successful outputs and fault real agent ownership/accounting/intake/rotation transitions on shared-store nodes.
3. Make disconnected dispatch or a reintroduced exclusivity defect fail the relevant acceptance case; run the complete offline quality gate and retain its evidence.

- **Done when:** The full scripts/quality passes with successful installed-runtime cases, and controlled reintroduction of disconnected dispatch or duplicate ownership causes the corresponding acceptance case to fail.
