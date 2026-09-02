---
id: load-content-as-json
title: "Load Content as a Document"
workstream: "0018"
kind: task
depends_on:
  - command-conformance-gate
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/LoadContentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/LoadContentResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/RepositoryValueKind.java
  - core/src/main/java/rs/slingshot/agent/command/content/LoadContentHandler.java
  - core/src/main/java/rs/slingshot/agent/command/content/package-info.java
  - policy/commands/load_content_as_json.toml
  - "schemas/agent-protocol/command/load_content_as_json-*.json"
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/LoadContentCommandTest.java
  - core/src/test/java/rs/slingshot/agent/command/content/LoadContentHandlerTest.java
  - core/src/main/java/rs/slingshot/agent/command/RegistryRow.java
  - core/src/test/java/rs/slingshot/agent/command/CommandRegistryTest.java
  - "core/src/test/resources/fixtures/command-registry/read-requiring-a-key/**"
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - core/src/test/resources/fixtures/agent-contract/sibling-command-classification.json
  - development/src/main/java/rs/slingshot/agent/development/CommandConformance.java
  - development/src/main/java/rs/slingshot/agent/development/ScenarioInventory.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/LoadContentScenario.java
  - interop/scenarios/load-content-as-json.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Load Content as a Document

The command everything else is compared against: one subtree, rendered exactly, with every repository value type either represented faithfully or refused by name. A loader that silently coerces a type it does not understand is a loader whose output nobody can trust to round-trip.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `LoadContentCommand` with a repository address and a depth, both required, so a caller never inherits a depth somebody else chose.
3. Implement `LoadContentResult` as the rendered subtree in canonical bytes, with every repository value type mapped explicitly and no type mapped by a default branch.
4. Declare exactly `not_found`, `access_denied`, `unsupported_repository_value`, `load_budget_exceeded`. An unsupported repository value is refused by name rather than rendered as a string, because a value nobody can round-trip is worse than a value nobody received.
5. Implement `LoadContentHandler` reading through the caller's read-only resolver, walking to exactly the requested depth and no further, and counting every node it examines against the load budget.

**Tests:**

- Every repository value type this build supports round-trips through the renderer byte-identically, and one it does not is refused naming the type and the property.
- The depth is honoured exactly: a subtree one level deeper than requested is not included, and a depth of zero yields the addressed node alone.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`load_content_as_json` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `load_content_as_json` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=LoadContentCommandTest && ./mvnw verify -pl aem -Dtest=LoadContentHandlerTest && ./mvnw verify -pl interop -Dtest=LoadContentScenario` proves faithful representation of every supported repository value type with an unsupported one refused by name, exact depth honouring including depth zero, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
