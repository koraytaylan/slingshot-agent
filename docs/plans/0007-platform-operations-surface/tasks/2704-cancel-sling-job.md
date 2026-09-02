---
id: cancel-sling-job
title: "Cancel a Sling Job"
workstream: "0027"
kind: task
depends_on:
  - inspect-sling-job
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/job/CancelSlingJobCommand.java
  - core/src/main/java/rs/slingshot/agent/command/job/CancelSlingJobResult.java
  - core/src/main/resources/registry/cancel_sling_job.toml
  - "schemas/commands/cancel_sling_job/**"
  - core/src/test/java/rs/slingshot/agent/command/job/CancelSlingJobCommandTest.java
  - "core/src/test/resources/fixtures/commands/cancel_sling_job/**"
  - aem/src/main/java/rs/slingshot/agent/aem/job/CancelSlingJobHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/job/CancelSlingJobHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CancelSlingJobScenario.java
  - interop/scenarios/cancel-sling-job.toml
status: done
merged_as: ""
---
# Cancel a Sling Job

Cancelling a job stops a retry loop, and it is the one platform control here that can be pointed at this agent's own work. Refusing to cancel this agent's own command jobs is not squeamishness: a cancelled command job leaves a logical operation whose fence nobody will release until it expires.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CancelSlingJobCommand` with the job identifier and a required expected state, using Plan 0006's guard vocabulary.
3. Implement `CancelSlingJobResult` as the job identifier and the state the platform reports afterwards, read back rather than assumed.
4. Declare exactly `job_not_found`, `job_not_cancellable`, `platform_control_rejected`, `platform_control_outcome_unknown`. `job_not_cancellable` covers both a job the platform will not cancel from its current state and one belonging to this agent's own command topic, and the refusal says which.
5. Implement `CancelSlingJobHandler` refusing this agent's own command topic before anything else, then comparing the expected state and cancelling through the platform's own interface.

**Tests:**

- A job on this agent's own command topic is refused as not cancellable, naming that reason, with the job asserted untouched.
- An expected state that does not match refuses with the job asserted in its original state.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`cancel_sling_job` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `cancel_sling_job` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CancelSlingJobCommandTest && ./mvnw verify -pl aem -Dtest=CancelSlingJobHandlerTest && ./mvnw verify -pl interop -Dtest=CancelSlingJobScenario` proves this agent's own command jobs refused with the reason named and the job untouched, a required expected state refusing a mismatched cancellation, and a state read back rather than assumed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
