---
id: console-runtime-assembly
title: "Connect the Installed Console Data Sources"
workstream: "0045"
kind: task
depends_on:
  - runtime-command-assembly
  - state-access-and-ownership
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/console
  - core/src/main/java/rs/slingshot/agent/health
  - ui.apps/src/main/content/jcr_root/apps/slingshot-agent
  - aem/src/main/java/rs/slingshot/agent/aem
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ConsoleRenderScenario.java
status: pending
merged_as: ""
---
# Connect the Installed Console Data Sources

Finding(s): R01; R16 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Register implementations for the datasource resource types referenced by the shipped console and bind them to the live authorized state services.
2. Install ui.apps and render operations, detail, maintenance and health with populated state as permitted and denied callers.
3. Exercise artifact links and live-tail lifecycle against the actual transport and verify inaccessible records do not appear.

- **Done when:** The installed authorized console renders populated operation/diagnostic data and functional links/tail, while denied callers cannot obtain another operation's data.
