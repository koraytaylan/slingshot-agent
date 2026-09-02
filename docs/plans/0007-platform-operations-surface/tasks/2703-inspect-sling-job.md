---
id: inspect-sling-job
title: "Inspect a Sling Job"
workstream: "0027"
kind: task
depends_on:
  - find-sling-jobs
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/job/InspectSlingJobCommand.java
  - core/src/main/java/rs/slingshot/agent/command/job/InspectSlingJobResult.java
  - core/src/main/resources/registry/inspect_sling_job.toml
  - "schemas/commands/inspect_sling_job/**"
  - core/src/test/java/rs/slingshot/agent/command/job/InspectSlingJobCommandTest.java
  - "core/src/test/resources/fixtures/commands/inspect_sling_job/**"
  - aem/src/main/java/rs/slingshot/agent/aem/job/InspectSlingJobHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/job/InspectSlingJobHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/InspectSlingJobScenario.java
  - interop/scenarios/inspect-sling-job.toml
status: done
merged_as: ""
---
# Inspect a Sling Job

Why a job keeps failing is the question, and the answer is its retry count, its last error, and its progress — not its arguments. Reporting which properties exist without reporting what they hold is the same two-phase idea configuration inspection uses, applied where there is no metatype to consult at all.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `InspectSlingJobCommand` with the job identifier and nothing else.
3. Implement `InspectSlingJobResult` as the topic, state, queue, retry count, creation and finish instants, the platform's own result message, and the names of the properties it carries — names only, with no value and no declared type, because nothing describes them.
4. Declare exactly `job_not_found`, `job_inventory_failed`, `result_budget_exceeded`. `result_budget_exceeded` covers a platform result message longer than the bound, refused rather than truncated, because a truncated error message is the one that misleads.
5. Implement `InspectSlingJobHandler` reading the platform's own job record after the permitted-group check.

**Tests:**

- Property names are reported and no value is, asserted over a job whose property values are credential-shaped.
- A platform result message past the bound is refused rather than truncated, and the bound is proved at exactly its limit and one past.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`inspect_sling_job` at 262144 bytes).
- The operation-key rule is proved from the row rather than restated: `inspect_sling_job` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=InspectSlingJobCommandTest && ./mvnw verify -pl aem -Dtest=InspectSlingJobHandlerTest && ./mvnw verify -pl interop -Dtest=InspectSlingJobScenario` proves property names without values for credential-shaped inputs, a refused rather than truncated over-bound platform message, and the retry count and error reported, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
