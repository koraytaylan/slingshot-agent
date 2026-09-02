---
id: inspect-workflow-instance
title: "Inspect a Workflow Instance"
workstream: "0026"
kind: task
depends_on:
  - find-workflow-instances
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/workflow/InspectWorkflowInstanceCommand.java
  - core/src/main/java/rs/slingshot/agent/command/workflow/InspectWorkflowInstanceResult.java
  - core/src/main/resources/registry/inspect_workflow_instance.toml
  - "schemas/commands/inspect_workflow_instance/**"
  - core/src/test/java/rs/slingshot/agent/command/workflow/InspectWorkflowInstanceCommandTest.java
  - "core/src/test/resources/fixtures/commands/inspect_workflow_instance/**"
  - aem/src/main/java/rs/slingshot/agent/aem/workflow/InspectWorkflowInstanceHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/workflow/InspectWorkflowInstanceHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/InspectWorkflowInstanceScenario.java
  - interop/scenarios/inspect-workflow-instance.toml
status: done
merged_as: ""
---
# Inspect a Workflow Instance

Where a stuck workflow actually is: which step, since when, and what it is waiting for. That is the answer, and the workflow's own metadata — which is where whatever started it put its arguments — is not part of it.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `InspectWorkflowInstanceCommand` with the instance identifier and nothing else.
3. Implement `InspectWorkflowInstanceResult` as the model, the state, the payload address, the current step with the instant it was entered, and the completed steps in order, with no metadata value.
4. Declare exactly `instance_not_found`, `instance_access_denied`, `workflow_inventory_failed`, `result_budget_exceeded`. `instance_access_denied` is distinct from `instance_not_found` even though both are ordinary, because the first tells a caller the instance exists and their group does not reach it.
5. Implement `InspectWorkflowInstanceHandler` reading the platform's own instance record after the permitted-group check.

**Tests:**

- The current step and the instant it was entered are reported, proved against an instance deliberately held at a step.
- No result carries a metadata value, asserted over an instance started with distinctive metadata.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`inspect_workflow_instance` at 262144 bytes).
- The operation-key rule is proved from the row rather than restated: `inspect_workflow_instance` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=InspectWorkflowInstanceCommandTest && ./mvnw verify -pl aem -Dtest=InspectWorkflowInstanceHandlerTest && ./mvnw verify -pl interop -Dtest=InspectWorkflowInstanceScenario` proves the current step with its entry instant and the completed steps in order, no metadata value disclosed, and both sides of the result bound, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
