---
id: runtime-lifecycle-services
title: "Activate Durable State Lifecycle Services"
workstream: "0045"
kind: task
depends_on:
  - bounded-maintenance-pages
  - atomic-generation-rotation
  - fenced-continuation-authority
  - state-access-and-ownership
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store
  - core/src/main/java/rs/slingshot/agent/execution/RestartRecovery.java
  - core/src/main/java/rs/slingshot/agent/health
  - core/src/main/java/rs/slingshot/agent/http/CapabilityServlet.java
  - core/pom.xml
  - ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config
  - interop/src/test/java/rs/slingshot/agent/interop/tier/
status: pending
merged_as: ""
---
# Activate Durable State Lifecycle Services

Finding(s): R01; R09; R10 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Compose state initialization, continuation authority, conservative recovery and maintenance as real DS/scheduler services with the required service identity.
2. Read durable serving generation and authority readiness for discovery and health rather than constants, and expose actionable initialization failure.
3. Restart/update the installed bundle with populated state, verify scheduler execution/cleanup and resource shutdown, and keep deferred caller execution refused.

- **Done when:** Installed services initialize and recover populated state, run bounded maintenance, report actual durable generation/key readiness and release resources on restart, without widening caller privileges.
