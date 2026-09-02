---
id: quality-workflow
title: "Quality Workflow"
workstream: "0039"
kind: task
depends_on:
  - platform-version-floor
gated: false
touches:
  - .github/workflows/quality.yml
  - development/src/main/java/rs/slingshot/agent/development/WorkflowPolicy.java
  - development/src/test/java/rs/slingshot/agent/development/WorkflowPolicyTest.java
  - "development/src/test/resources/fixtures/workflow-policy/**"
status: done
merged_as: ""
---
# Quality Workflow

The gate a contributor runs and the gate continuous integration runs have to be the same gate, or the second one is a different opinion that happens to be authoritative. So the workflow runs `scripts/quality` and nothing else.

**Steps:**

1. Author the policy fixtures first: a workflow with an unpinned action, one with a broader permission than it needs, one persisting credentials on checkout, and one letting an expression reach a shell.
2. Write `.github/workflows/quality.yml` whose only step after checkout and setup is `scripts/quality`, with no additional command and no option.
3. Pin every non-local action to a full commit, declare least privilege at the job rather than the workflow level, and disable credential persistence on checkout.
4. Let no workflow expression and no value a caller controls reach a shell, passing anything variable through the environment instead.
5. Implement `WorkflowPolicy` parsing every workflow into its real structure rather than matching text, and checking all four rules plus the rule that the quality workflow runs the gate and nothing else.

**Tests:**

- An unpinned action, a version tag rather than a commit, an over-broad permission, persisted credentials, and an expression reaching a shell are five distinct findings.
- The quality workflow is asserted to run `scripts/quality` and no other command, and a fixture adding one is rejected.
- The policy parses workflow structure rather than matching text, proved by a fixture naming a forbidden construct inside a string.
- Every job's permissions are asserted to be the narrowest that job needs, compared against a declared requirement per job.
- The workflow is asserted to add no argument to the gate, since the gate takes none.

- **Done when:** `./mvnw verify -pl development -Dtest=WorkflowPolicyTest` proves five distinct workflow findings, a quality workflow running only the argument-free gate, structural parsing rather than text matching, and per-job least privilege compared against declared requirements.
