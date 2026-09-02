---
id: start-workflow
title: "Start a Workflow"
workstream: "0026"
kind: task
depends_on:
  - list-workflow-models
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/workflow/StartWorkflowCommand.java
  - core/src/main/java/rs/slingshot/agent/command/workflow/StartWorkflowResult.java
  - core/src/main/resources/registry/start_workflow.toml
  - "schemas/commands/start_workflow/**"
  - core/src/test/java/rs/slingshot/agent/command/workflow/StartWorkflowCommandTest.java
  - "core/src/test/resources/fixtures/commands/start_workflow/**"
  - aem/src/main/java/rs/slingshot/agent/aem/workflow/StartWorkflowHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/workflow/StartWorkflowHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/StartWorkflowScenario.java
  - interop/scenarios/start-workflow.toml
status: done
merged_as: ""
---
# Start a Workflow

A workflow runs as the platform rather than as the caller, which makes it the one command here that could do something the caller could not. A workflow acts on its payload — a publish workflow publishes it, an approval workflow changes it — so what has to be checked is the caller's ability to act on that content, not merely to look at it. Checking read access would leave a caller who can see a branch and not change it able to have the platform change it for them, which is the escalation this whole surface is built to refuse.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `StartWorkflowCommand` with the model identifier, the payload address, and the workflow metadata as an explicit map with a declared bound.
3. Implement `StartWorkflowResult` as the instance identifier the platform created and the model it was started from, so the caller can follow it with the inspect command.
4. Declare exactly `model_not_found`, `model_invalid`, `payload_not_found`, `payload_access_denied`, `metadata_rejected`, `platform_control_rejected`, `platform_control_outcome_unknown`. `payload_access_denied` covers a caller who cannot modify the payload as well as one who cannot read it, because the platform will act on it either way and a workflow started on content the caller could not have changed themselves is exactly the escalation this surface exists to refuse.
5. Implement `StartWorkflowHandler` resolving the payload through the caller's own session, checking that session's ability to modify it rather than only to read it, and refusing before the platform is touched, then starting through the platform's own workflow interface.

**Tests:**

- A payload the caller cannot read is refused before the platform is touched, proved by a workflow interface that would record any call.
- A payload the caller can read and cannot modify is refused the same way, so no workflow acts on content its starter could not have changed themselves.
- The metadata bound is proved at exactly its limit and one past it, and metadata is proved not to reach any log or event unredacted.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`start_workflow` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `start_workflow` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=StartWorkflowCommandTest && ./mvnw verify -pl aem -Dtest=StartWorkflowHandlerTest && ./mvnw verify -pl interop -Dtest=StartWorkflowScenario` proves a payload checked for the caller's own ability to modify it and refused before the platform is touched for a read-only caller as well as an unreadable payload, both sides of the metadata bound with no unredacted metadata reaching a log or event, and a reachable distinct unknown outcome, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
