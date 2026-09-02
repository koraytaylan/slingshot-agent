---
id: inspect-replication-agent
title: "Inspect a Replication Agent"
workstream: "0029"
kind: task
depends_on:
  - list-replication-agents
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/replication/InspectReplicationAgentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/replication/InspectReplicationAgentResult.java
  - core/src/main/resources/registry/inspect_replication_agent.toml
  - "schemas/commands/inspect_replication_agent/**"
  - core/src/test/java/rs/slingshot/agent/command/replication/InspectReplicationAgentCommandTest.java
  - "core/src/test/resources/fixtures/commands/inspect_replication_agent/**"
  - aem/src/main/java/rs/slingshot/agent/aem/replication/InspectReplicationAgentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/replication/InspectReplicationAgentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/InspectReplicationAgentScenario.java
  - interop/scenarios/inspect-replication-agent.toml
status: done
merged_as: ""
---
# Inspect a Replication Agent

Why an agent is not delivering, without saying where it delivers to. The last error and the last delivery instant are the diagnosis; the address is the secret; keeping the two apart is the entire point of this command existing separately from the listing.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `InspectReplicationAgentCommand` with the agent name and nothing else.
3. Implement `InspectReplicationAgentResult` as the name, kind, enablement, queue length, the last delivery instant, and the platform's last error message with any address component removed.
4. Declare exactly `agent_not_found`, `agent_access_denied`, `agent_inventory_failed`. `agent_inventory_failed` is distinct from `agent_not_found` because an agent that cannot be read and one that does not exist need different responses from an operator.
5. Implement `InspectReplicationAgentHandler` reading the platform's own agent record after the permitted-group check and removing address components from the error message before it is returned.

**Tests:**

- A last-error message containing the transport address is returned with that component removed rather than masked, proved against a fixture error carrying a credential-bearing URL.
- The redaction audit finds nothing across the whole command, including its refusals.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`inspect_replication_agent` at 262144 bytes).
- The operation-key rule is proved from the row rather than restated: `inspect_replication_agent` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=InspectReplicationAgentCommandTest && ./mvnw verify -pl aem -Dtest=InspectReplicationAgentHandlerTest && ./mvnw verify -pl interop -Dtest=InspectReplicationAgentScenario` proves an address component removed rather than masked from a credential-bearing error message with the redaction audit clean including refusals, and distinct inventory-failure and not-found refusals, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
