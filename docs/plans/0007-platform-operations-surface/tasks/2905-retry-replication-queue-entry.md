---
id: retry-replication-queue-entry
title: "Retry a Replication Queue Entry"
workstream: "0029"
kind: task
depends_on:
  - flush-replication-queue
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/replication/RetryReplicationQueueEntryCommand.java
  - core/src/main/java/rs/slingshot/agent/command/replication/RetryReplicationQueueEntryResult.java
  - core/src/main/resources/registry/retry_replication_queue_entry.toml
  - "schemas/commands/retry_replication_queue_entry/**"
  - core/src/test/java/rs/slingshot/agent/command/replication/RetryReplicationQueueEntryCommandTest.java
  - "core/src/test/resources/fixtures/commands/retry_replication_queue_entry/**"
  - aem/src/main/java/rs/slingshot/agent/aem/replication/RetryReplicationQueueEntryHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/replication/RetryReplicationQueueEntryHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/RetryReplicationQueueEntryScenario.java
  - interop/scenarios/retry-replication-queue-entry.toml
status: done
merged_as: ""
---
# Retry a Replication Queue Entry

The surgical alternative to flushing: one entry, by identifier, so an operator can clear a single blocked item without discarding everything behind it. Reporting the entry's new position is what tells them whether it actually moved.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `RetryReplicationQueueEntryCommand` with the agent name and the entry identifier, both required.
3. Implement `RetryReplicationQueueEntryResult` as the entry identifier and its position and attempt count after the retry, read back from the platform.
4. Declare exactly `agent_not_found`, `agent_access_denied`, `entry_not_found`, `platform_control_rejected`, `platform_control_outcome_unknown`. `entry_not_found` covers an entry that has already left the queue, which is the ordinary case when two operators are looking at the same problem.
5. Implement `RetryReplicationQueueEntryHandler` retrying through the platform's own interface after the permitted-group check and reading the entry's new state back.

**Tests:**

- An entry that has already left the queue is refused as not found, with the queue asserted unchanged.
- The reported position and attempt count are read back from the platform rather than assumed, proved by a fixture platform reporting a different position.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`retry_replication_queue_entry` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `retry_replication_queue_entry` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=RetryReplicationQueueEntryCommandTest && ./mvnw verify -pl aem -Dtest=RetryReplicationQueueEntryHandlerTest && ./mvnw verify -pl interop -Dtest=RetryReplicationQueueEntryScenario` proves an already-departed entry refused with the queue unchanged, and a position and attempt count read back from the platform rather than assumed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
