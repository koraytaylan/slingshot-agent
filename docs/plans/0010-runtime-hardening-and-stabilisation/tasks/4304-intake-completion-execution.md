---
id: intake-completion-execution
title: "Execute Fully Received Intake Operations Once"
workstream: "0043"
kind: task
depends_on:
  - atomic-intake-publication
  - reliable-terminal-finalization
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/ArtifactIntakeServlet.java
  - core/src/main/java/rs/slingshot/agent/http/IntakeSlotWrite.java
  - core/src/main/java/rs/slingshot/agent/http/SubmitServlet.java
  - core/src/main/java/rs/slingshot/agent/execution
  - core/src/test/java/rs/slingshot/agent/http/ArtifactIntakeServletTest.java
  - core/src/test/java/rs/slingshot/agent/http/SubmitServletTest.java
status: complete
merged_as: "6bb7d69"
---
# Execute Fully Received Intake Operations Once

Finding(s): R04 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Drive a valid declared multi-slot submission through the installed transport and demonstrate the current accepted-but-never-started state.
2. Connect validated last-slot completion and retry to one exclusive start under the appropriate live caller request.
3. Test interrupted and simultaneous final-slot requests, repeated uploads, lost responses and complete resubmission.

- **Done when:** A fully received valid operation runs exactly once and yields a retrievable outcome, while an incomplete or invalid manifest never starts a command.
