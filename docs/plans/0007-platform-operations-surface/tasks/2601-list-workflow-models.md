---
id: list-workflow-models
title: "List Workflow Models"
workstream: "0026"
kind: task
depends_on:
  - list-components
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/workflow/ListWorkflowModelsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/workflow/ListWorkflowModelsResult.java
  - core/src/main/resources/registry/list_workflow_models.toml
  - "schemas/commands/list_workflow_models/**"
  - core/src/test/java/rs/slingshot/agent/command/workflow/ListWorkflowModelsCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_workflow_models/**"
  - aem/src/main/java/rs/slingshot/agent/aem/workflow/ListWorkflowModelsHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/workflow/ListWorkflowModelsHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListWorkflowModelsScenario.java
  - interop/scenarios/list-workflow-models.toml
status: done
merged_as: ""
---
# List Workflow Models

Starting a workflow needs a model identifier, and an operator who has to find one in a console is an operator who will paste the wrong one. This is the lookup that makes the next command usable, and it reports the identifier the platform actually accepts rather than the one it displays.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListWorkflowModelsCommand` with a result window and an optional continuation token, and no filter, because a partial view of available models makes a caller believe a model does not exist.
3. Implement `ListWorkflowModelsResult` as each model's identifier, its title, and its version, with the identifier being the value the platform accepts when starting a workflow.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `workflow_inventory_failed`. A workflow inventory that cannot be read is a refusal rather than an empty page, because an empty model list reads as a deployment with no workflows.
5. Implement `ListWorkflowModelsHandler` reading the platform's own model inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- The reported identifier is proved to be the one the start command accepts, by starting a workflow with every listed identifier in the interop scenario.
- Ordering is stable across pages, proved against a single unbounded read.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_workflow_models` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_workflow_models` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListWorkflowModelsCommandTest && ./mvnw verify -pl aem -Dtest=ListWorkflowModelsHandlerTest && ./mvnw verify -pl interop -Dtest=ListWorkflowModelsScenario` proves identifiers proved acceptable to the start command for every listed model, stable ordering across pages, and a refused rather than empty answer when the inventory cannot be read, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
