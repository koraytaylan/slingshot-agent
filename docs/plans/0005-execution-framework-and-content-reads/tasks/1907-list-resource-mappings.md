---
id: list-resource-mappings
title: "List Resource Mappings"
workstream: "0019"
kind: task
depends_on:
  - resolve-and-map-resource-path
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/resolution/ListResourceMappingsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/resolution/ListResourceMappingsResult.java
  - core/src/main/resources/registry/list_resource_mappings.toml
  - "schemas/commands/list_resource_mappings/**"
  - core/src/test/java/rs/slingshot/agent/command/resolution/ListResourceMappingsCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_resource_mappings/**"
  - aem/src/main/java/rs/slingshot/agent/aem/resolution/ListResourceMappingsHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/resolution/ListResourceMappingsHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListResourceMappingsScenario.java
  - interop/scenarios/list-resource-mappings.toml
status: done
merged_as: ""
---
# List Resource Mappings

The rules themselves, so an operator can read the configuration rather than infer it from twenty resolutions. A mapping entry can carry a credential in its address, which is why this listing has a redaction rule of its own.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListResourceMappingsCommand` with a result window and an optional continuation token, and no filter, because a partial view of resolution rules is a misleading one.
3. Implement `ListResourceMappingsResult` as the entries in the order the platform applies them, each with its pattern, its replacement, and its declared kind, with any credential component of an address removed rather than masked.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `mapping_inventory_failed`. A mapping inventory that cannot be read is a refusal rather than an empty list, because an empty list of mapping rules reads as a deployment with none.
5. Implement `ListResourceMappingsHandler` reading the platform's own mapping inventory under the caller's session and preserving its order exactly.

**Tests:**

- The entries are reported in the platform's own application order, proved against a fixture whose declaration order differs from its application order.
- A mapping whose address carries a credential component is reported with that component removed rather than masked, and the redaction audit finds nothing across the whole listing.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_resource_mappings` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_resource_mappings` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListResourceMappingsCommandTest && ./mvnw verify -pl aem -Dtest=ListResourceMappingsHandlerTest && ./mvnw verify -pl interop -Dtest=ListResourceMappingsScenario` proves entries in the platform's own application order under a differing declaration order, and credential components removed rather than masked with the redaction audit clean, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
