---
id: find-workflow-instances
title: "Find Workflow Instances"
workstream: "0026"
kind: task
depends_on:
  - start-workflow
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/workflow/FindWorkflowInstancesCommand.java
  - core/src/main/java/rs/slingshot/agent/command/workflow/FindWorkflowInstancesResult.java
  - core/src/main/resources/registry/find_workflow_instances.toml
  - "schemas/commands/find_workflow_instances/**"
  - core/src/test/java/rs/slingshot/agent/command/workflow/FindWorkflowInstancesCommandTest.java
  - "core/src/test/resources/fixtures/commands/find_workflow_instances/**"
  - aem/src/main/java/rs/slingshot/agent/aem/workflow/FindWorkflowInstancesHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/workflow/FindWorkflowInstancesHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/FindWorkflowInstancesScenario.java
  - interop/scenarios/find-workflow-instances.toml
status: done
merged_as: ""
---
# Find Workflow Instances

The question an operator asks when something has been stuck for two days. Finding by model, by state, and by payload root is what makes an answer actionable, and reporting the payload address is what makes the next step obvious.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindWorkflowInstancesCommand` with an optional model identifier, an optional state, an optional payload root, a result window, and an optional continuation token.
3. Implement `FindWorkflowInstancesResult` as each instance's identifier, model, state, payload address, and start instant, with no workflow metadata value.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `workflow_inventory_failed`. A workflow inventory that cannot be read is a refusal rather than an empty page, for the same reason listing models is.
5. Implement `FindWorkflowInstancesHandler` reading the platform's own instance inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- Every combination of the three optional filters selects correctly, one filter at a time and all together, against a fixture set covering each.
- No result carries a workflow metadata value, asserted over instances started with distinctive metadata.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_workflow_instances` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_workflow_instances` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindWorkflowInstancesCommandTest && ./mvnw verify -pl aem -Dtest=FindWorkflowInstancesHandlerTest && ./mvnw verify -pl interop -Dtest=FindWorkflowInstancesScenario` proves correct selection for every filter combination one at a time and together, no metadata value disclosed for instances carrying distinctive metadata, and stable ordering across pages, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
