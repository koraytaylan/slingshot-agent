---
id: resolve-and-map-resource-path
title: "Resolve and Map a Resource Address"
workstream: "0019"
kind: task
depends_on:
  - download-content-package
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/ResolutionDirection.java
  - core/src/main/java/rs/slingshot/agent/command/content/ResourceResolutionCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/MapResourcePathArgument.java
  - core/src/main/java/rs/slingshot/agent/command/content/ResourceResolutionResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/ResourceResolutionHandler.java
  - policy/commands/resolve_resource_path.toml
  - policy/commands/map_resource_path.toml
  - "schemas/agent-protocol/command/resolve_resource_path-*.json"
  - "schemas/agent-protocol/command/map_resource_path-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/ResourceResolutionCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ResourceResolutionScenario.java
  - interop/scenarios/resolve-resource-path.toml
  - interop/scenarios/map-resource-path.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Resolve and Map a Resource Address

The two directions of the same machinery, and the reason a link is wrong on a published site more often than anything else. They are one task because they are one subject and because proving them apart would leave the round trip — the thing operators actually care about — unproved.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ResourceResolutionCommand` in two forms sharing one argument type: an inbound address to resolve, or an outbound address to map, with the request address required for the resolving direction because resolution depends on it.
3. Implement `ResourceResolutionResult` as the resulting address and the ordered mapping entries that produced it, so an operator sees which rule decided rather than only what it decided.
4. Declare for `resolve_resource_path` exactly `resolution_failed`, `resolution_budget_exceeded`, `request_address_rejected`; and for `map_resource_path` exactly `resolution_failed`, `resolution_budget_exceeded`. A request address the mapping rules reject is a distinct refusal from a resolution that simply produced nothing, because one is a malformed question and the other is a correct answer of no.
5. Implement `ResourceResolutionHandler` using the platform's own resolver under the caller's session, and reporting the entries it applied rather than recomputing them.

**Tests:**

- A round trip through both directions returns the original address for every mapping fixture, and a fixture where it does not is reported rather than hidden.
- The applied mapping entries are reported in the order the platform applied them, proved against a fixture with overlapping rules.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`resolve_resource_path` at 262144 bytes; `map_resource_path` at 262144 bytes).
- The operation-key rule is proved from the row rather than restated: `resolve_resource_path` refuses an operation key and a submission carrying one is refused; `map_resource_path` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ResourceResolutionCommandTest && ./mvnw verify -pl aem -Dtest=ResourceResolutionHandlerTest && ./mvnw verify -pl interop -Dtest=ResourceResolutionScenario` proves a proved round trip through both directions across every mapping fixture, and applied entries reported in the platform's own order under overlapping rules, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
