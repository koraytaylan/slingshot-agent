---
id: flush-replication-queue
title: "Flush a Replication Queue"
workstream: "0029"
kind: task
depends_on:
  - inspect-replication-queue
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/replication/FlushReplicationQueueCommand.java
  - core/src/main/java/rs/slingshot/agent/command/replication/FlushReplicationQueueResult.java
  - core/src/main/resources/registry/flush_replication_queue.toml
  - "schemas/commands/flush_replication_queue/**"
  - core/src/test/java/rs/slingshot/agent/command/replication/FlushReplicationQueueCommandTest.java
  - "core/src/test/resources/fixtures/commands/flush_replication_queue/**"
  - aem/src/main/java/rs/slingshot/agent/aem/replication/FlushReplicationQueueHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/replication/FlushReplicationQueueHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/FlushReplicationQueueScenario.java
  - interop/scenarios/flush-replication-queue.toml
status: done
merged_as: ""
---
# Flush a Replication Queue

Emptying a queue discards work nobody can get back, and doing it against a queue that has changed since the operator looked is how the wrong work gets discarded. The expected length is the guard, and it is required for exactly that reason.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FlushReplicationQueueCommand` with the agent name and a required expected queue length, using Plan 0006's guard vocabulary.
3. Implement `FlushReplicationQueueResult` as the agent name and the number of entries actually discarded.
4. Declare exactly `agent_not_found`, `agent_access_denied`, `queue_expectation_mismatch`, `platform_control_rejected`, `platform_control_outcome_unknown`. `queue_expectation_mismatch` is the guard doing its job and is distinct from a platform rejection, because one means the queue moved and the other means the platform said no.
5. Implement `FlushReplicationQueueHandler` comparing the expected length against the platform's report and refusing before discarding anything.

**Tests:**

- A queue whose length differs from the expectation is refused with the queue asserted unchanged entry for entry.
- The discarded count is read back from the platform rather than assumed from the expectation.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`flush_replication_queue` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `flush_replication_queue` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FlushReplicationQueueCommandTest && ./mvnw verify -pl aem -Dtest=FlushReplicationQueueHandlerTest && ./mvnw verify -pl interop -Dtest=FlushReplicationQueueScenario` proves a required expected length refusing a changed queue with every entry unchanged, and a discarded count read back from the platform rather than assumed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
