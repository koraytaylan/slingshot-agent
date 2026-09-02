---
id: inspect-replication-queue
title: "Inspect a Replication Queue"
workstream: "0029"
kind: task
depends_on:
  - inspect-replication-agent
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/replication/InspectReplicationQueueCommand.java
  - core/src/main/java/rs/slingshot/agent/command/replication/InspectReplicationQueueResult.java
  - core/src/main/resources/registry/inspect_replication_queue.toml
  - "schemas/commands/inspect_replication_queue/**"
  - core/src/test/java/rs/slingshot/agent/command/replication/InspectReplicationQueueCommandTest.java
  - "core/src/test/resources/fixtures/commands/inspect_replication_queue/**"
  - aem/src/main/java/rs/slingshot/agent/aem/replication/InspectReplicationQueueHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/replication/InspectReplicationQueueHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/InspectReplicationQueueScenario.java
  - interop/scenarios/inspect-replication-queue.toml
status: done
merged_as: ""
---
# Inspect a Replication Queue

The entries themselves, which is what an operator needs to decide between retrying one and flushing all of them. Each entry names the content it is about, which is the caller's own repository, and nothing about where it was going.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `InspectReplicationQueueCommand` with the agent name, a result window, and an optional continuation token.
3. Implement `InspectReplicationQueueResult` as each entry's identifier, the content address it concerns, its action, its attempt count, its queue position, and its last error with address components removed.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `agent_not_found`, `agent_access_denied`, `queue_inventory_failed`. `queue_inventory_failed` is distinct from an agent that does not exist, because a queue that cannot be read on an agent that does is the interesting case.
5. Implement `InspectReplicationQueueHandler` reading the platform's own queue after the permitted-group check, in the platform's own order across pages.

**Tests:**

- Entries are reported in the platform's own queue order across pages, proved against a single unbounded read.
- No entry carries a transport address, asserted over a queue on a credential-bearing agent.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`inspect_replication_queue` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `inspect_replication_queue` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=InspectReplicationQueueCommandTest && ./mvnw verify -pl aem -Dtest=InspectReplicationQueueHandlerTest && ./mvnw verify -pl interop -Dtest=InspectReplicationQueueScenario` proves entries in the platform's own queue order across pages against an unbounded read, no transport address disclosed on a credential-bearing agent, and distinct queue-inventory and agent-not-found refusals, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
