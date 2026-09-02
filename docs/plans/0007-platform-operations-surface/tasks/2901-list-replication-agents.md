---
id: list-replication-agents
title: "List Replication Agents"
workstream: "0029"
kind: task
depends_on:
  - list-group-members
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/replication/ListReplicationAgentsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/replication/ListReplicationAgentsResult.java
  - core/src/main/resources/registry/list_replication_agents.toml
  - "schemas/commands/list_replication_agents/**"
  - core/src/test/java/rs/slingshot/agent/command/replication/ListReplicationAgentsCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_replication_agents/**"
  - aem/src/main/java/rs/slingshot/agent/aem/replication/ListReplicationAgentsHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/replication/ListReplicationAgentsHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListReplicationAgentsScenario.java
  - interop/scenarios/list-replication-agents.toml
status: done
merged_as: ""
---
# List Replication Agents

A replication agent's transport address is a URL, and a replication transport URL very frequently carries credentials in its own userinfo. So the address is not a field on this command, and that absence is the whole design.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListReplicationAgentsCommand` with a result window and an optional continuation token, and no filter over transport configuration.
3. Implement `ListReplicationAgentsResult` as each agent's name, its kind, whether it is enabled, and its queue's current length, with no transport address and no transport credential.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `agent_inventory_failed`. An agent inventory that cannot be read is a refusal rather than an empty page, because an empty agent list reads as an author with no replication configured.
5. Implement `ListReplicationAgentsHandler` reading the platform's own agent inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- No result carries a transport address, asserted over the result type and over agents configured with credential-bearing addresses.
- A filter over transport configuration is refused at construction rather than ignored.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_replication_agents` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_replication_agents` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListReplicationAgentsCommandTest && ./mvnw verify -pl aem -Dtest=ListReplicationAgentsHandlerTest && ./mvnw verify -pl interop -Dtest=ListReplicationAgentsScenario` proves no transport address reachable in the type or the response even for credential-bearing configurations, a transport filter refused at construction, and stable ordering across pages, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
