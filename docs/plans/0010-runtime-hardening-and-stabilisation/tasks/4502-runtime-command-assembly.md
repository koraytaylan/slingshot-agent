---
id: runtime-command-assembly
title: "Connect the Packaged Immediate Command Runtime"
workstream: "0045"
kind: task
depends_on:
  - runtime-lifecycle-services
  - intake-completion-execution
  - terminal-result-delivery
  - correct-handler-pagination
  - complete-reference-guards
  - bounded-component-deletion
  - real-package-publication
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/http/CapabilityServlet.java
  - core/src/main/java/rs/slingshot/agent/command
  - core/pom.xml
  - aem/src/main/java/rs/slingshot/agent/aem
  - aem/pom.xml
  - policy/commands
  - policy/imported-packages.toml
  - interop/src/test/java/rs/slingshot/agent/interop/tier/
status: complete
merged_as: "dbe99e5"
---
# Connect the Packaged Immediate Command Runtime

Finding(s): R01 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Embed a validated command registry in the product and compose dispatch, argument readers, caller context, result assembly and actual supported platform adapters through DS.
2. Advertise only commands whose implementation and deployment prerequisites are active; fail clearly when a required adapter is missing.
3. Install the built product in the runnable public Sling tier and execute supported immediate reads/mutations from real transport envelopes, verifying independent side effects and outcomes.

- **Done when:** The shipped public-runtime package advertises and successfully executes its supported registry commands using packaged data and active adapters, and never accepts work for a missing implementation. Licensed owner runtimes are optional external validation.
