---
id: executable-owner-tiers
title: "Keep Licensed Owner Tiers Optional"
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
status: complete
merged_as: 9f4217c
---
# Keep Licensed Owner Tiers Optional

Finding(s): R16 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Keep missing/digest/acknowledgement refusal tests distinct from the optional entrypoints; do not fetch or manufacture owner credentials, licensed jars or client binaries.
2. Leave the executable owner-tier entrypoints available for a separately authorized environment, without making them part of the Plan 10 or release completion gate.
3. Record any owner-run identities and round trips when they exist, while treating absent inputs as an expected optional state.

- **Done when:** The public acceptance tier and full quality gate do not require owner inputs, and the optional entrypoints refuse absent or invalid inputs distinctly rather than passing absence assertions.
