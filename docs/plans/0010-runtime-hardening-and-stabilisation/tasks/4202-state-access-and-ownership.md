---
id: state-access-and-ownership
title: "Separate State Sessions and Enforce Ownership"
workstream: "0042"
kind: task
depends_on:
  - live-operator-configuration
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/repository/AgentSession.java
  - core/src/main/java/rs/slingshot/agent/http
  - core/src/main/java/rs/slingshot/agent/execution
  - core/src/main/java/rs/slingshot/agent/stream/StreamSession.java
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionLedger.java
  - core/src/main/java/rs/slingshot/agent/store/SubscriptionRecord.java
  - core/src/test/java/rs/slingshot/agent/execution
  - core/src/test/java/rs/slingshot/agent/store
  - core/src/test/java/rs/slingshot/agent/proof
  - interop/src/main/java/rs/slingshot/agent/interop/tier
  - interop/src/test/java/rs/slingshot/agent/interop/tier
  - interop/scenarios
  - policy/design-patterns.toml
  - docs/SECURITY.md
  - core/src/test/java/rs/slingshot/agent/http
  - core/src/test/java/rs/slingshot/agent/repository
  - core/src/test/java/rs/slingshot/agent/stream
  - ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config
status: complete
merged_as: c4e2f86
---
# Separate State Sessions and Enforce Ownership

Finding(s): R03 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Add explicit owner/operator checks for snapshots, jobs, high-water, streams, artifacts and intake, reading ownership from durable records.
2. Use the narrow service session for internal state and retain the original request resolver for content effects; persist and verify subscription/operation binding.
3. Exercise owner, configured operator, unrelated state reader, removed operator and anonymous callers; verify denied content actions remain denied with byte-identical content.

- **Done when:** Every state route enforces the declared owner/operator rule, a permitted non-admin needs no direct state-tree write grant, and all requested content effects remain limited to the original caller's permissions.

Direct implementation is complete; review loops, atomic admission and scoped-session regressions,
installed ownership/content-permission proof, and full-gate verification are recorded in
[EXECUTION.md](../EXECUTION.md#4202--state-access-and-ownership).
