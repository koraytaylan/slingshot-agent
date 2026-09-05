---
id: live-operator-configuration
title: "Apply Operator Group Configuration at Runtime"
workstream: "0042"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/AuthorizationGate.java
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/console/ConsoleAuthority.java
  - core/src/test/java/rs/slingshot/agent/http
  - core/src/test/java/rs/slingshot/agent/console
  - ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/rs.slingshot.agent.http.AuthorizationGate.cfg.json
status: pending
merged_as: ""
---
# Apply Operator Group Configuration at Runtime

Finding(s): R03 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Bind the existing operator-group configuration to a real DS lifecycle and expose one current authorization source.
2. Use that source for submission and operator diagnostics, preserving distinct unknown-group and nonmember decisions internally.
3. Install the bundle and change configuration with dedicated users/groups, including removal of administrators from the configured set.

- **Done when:** Live configuration changes admit a newly permitted group and revoke a removed group on subsequent requests without a bundle restart or hardcoded administrator bypass.
