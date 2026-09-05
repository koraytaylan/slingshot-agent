---
id: executable-owner-tiers
title: "Make Acknowledged AEM and Client Tiers Execute"
workstream: "0046"
kind: task
depends_on:
  - positive-runtime-acceptance
gated: false
touches:
  - scripts/interop_quickstart_tier
  - scripts/interop_client_tier
  - interop/src/main/java/rs/slingshot/agent/interop/tier/QuickstartTier.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/ClientTier.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/QuickstartTierTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ClientConformanceScenario.java
  - support/quickstart-tier.toml
  - support/client-tier.toml
  - support/acceptance-matrix.toml
  - docs/INTEROP.md
  - docs/RELEASING.md
status: pending
merged_as: ""
---
# Make Acknowledged AEM and Client Tiers Execute

Finding(s): R16 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Separate missing/digest/acknowledgement refusal tests from executable tier entrypoints; do not fetch or manufacture owner credentials, licensed jars or client binaries.
2. For valid acknowledged inputs, install the full package into the selected AEM runtime and run the pinned actual sibling client against it.
3. Record exact runtime/package/client/contract identities and positive round trips; leave missing-input deployment rows explicitly unproved and update only claims supported by observed results.

- **Done when:** Valid acknowledged owner inputs run real AEM/full-package and sibling-client acceptance scenarios with successful exchanges and recorded identities; absent or invalid inputs refuse distinctly rather than passing absence assertions.
