---
id: find-sling-jobs
title: "Find Sling Jobs"
workstream: "0027"
kind: task
depends_on:
  - list-sling-job-queues
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/job/FindSlingJobsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/job/FindSlingJobsResult.java
  - core/src/main/resources/registry/find_sling_jobs.toml
  - "schemas/commands/find_sling_jobs/**"
  - core/src/test/java/rs/slingshot/agent/command/job/FindSlingJobsCommandTest.java
  - "core/src/test/resources/fixtures/commands/find_sling_jobs/**"
  - aem/src/main/java/rs/slingshot/agent/aem/job/FindSlingJobsHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/job/FindSlingJobsHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/FindSlingJobsScenario.java
  - interop/scenarios/find-sling-jobs.toml
status: done
merged_as: ""
---
# Find Sling Jobs

A job's properties are where whatever feature created it put its own arguments, and those arguments belong to that feature rather than to this agent. So this command finds jobs and never says what they carry, which is enough to diagnose a queue and not enough to read somebody else's inputs.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindSlingJobsCommand` with an optional topic, an optional state, a result window, and an optional continuation token, with no filter over job properties.
3. Implement `FindSlingJobsResult` as each job's identifier, topic, state, queue, retry count, and creation instant, and never a property value.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `job_inventory_failed`. A job inventory that cannot be read is a refusal rather than an empty page, for the same reason the queue listing refuses.
5. Implement `FindSlingJobsHandler` reading the platform's own job inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- No result carries a job property value, asserted over jobs whose properties hold distinctive values including credential-shaped ones.
- A filter over a job property is refused at construction rather than ignored.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_sling_jobs` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_sling_jobs` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindSlingJobsCommandTest && ./mvnw verify -pl aem -Dtest=FindSlingJobsHandlerTest && ./mvnw verify -pl interop -Dtest=FindSlingJobsScenario` proves no job property value disclosed even for credential-shaped values, a property filter refused at construction, and stable ordering across pages, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
