---
id: list-sling-job-queues
title: "List Sling Job Queues"
workstream: "0027"
kind: task
depends_on:
  - set-workflow-instance-suspension
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/job/ListJobQueuesCommand.java
  - core/src/main/java/rs/slingshot/agent/command/job/ListJobQueuesResult.java
  - core/src/main/resources/registry/list_sling_job_queues.toml
  - "schemas/commands/list_sling_job_queues/**"
  - core/src/test/java/rs/slingshot/agent/command/job/ListJobQueuesCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_sling_job_queues/**"
  - aem/src/main/java/rs/slingshot/agent/aem/job/ListJobQueuesHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/job/ListJobQueuesHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListJobQueuesScenario.java
  - interop/scenarios/list-sling-job-queues.toml
status: done
merged_as: ""
---
# List Sling Job Queues

A queue that is not draining is the cause of most of the mysteries an author instance produces, and its depth over time is the diagnosis. This is also this agent looking at the machinery it runs on, which is worth noticing.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListJobQueuesCommand` with a result window and an optional continuation token, and no filter, because a partial view of queues hides the one that is stuck.
3. Implement `ListJobQueuesResult` as each queue's name, its type, its current depth, its active count, and its `SuspensionState`, with no job identifier and no job property — the named type rather than a boolean, since the concept already has one.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `job_inventory_failed`. A job inventory that cannot be read is a refusal rather than an empty page, because an empty queue list is a platform that could not run.
5. Implement `ListJobQueuesHandler` reading the platform's own queue inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- The agent's own command queue appears in the listing like any other, and is not filtered out.
- No result carries a job identifier or a job property, asserted over queues holding jobs with distinctive properties.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_sling_job_queues` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_sling_job_queues` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListJobQueuesCommandTest && ./mvnw verify -pl aem -Dtest=ListJobQueuesHandlerTest && ./mvnw verify -pl interop -Dtest=ListJobQueuesScenario` proves the agent's own queue listed like any other, no job identifier or property disclosed, and a refused rather than empty answer when the inventory cannot be read, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
