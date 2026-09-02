---
id: list-components
title: "List Components"
workstream: "0025"
kind: task
depends_on:
  - set-bundle-state
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/ListComponentsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/ListComponentsResult.java
  - core/src/main/resources/registry/list_open_service_gateway_initiative_components.toml
  - "schemas/commands/list_open_service_gateway_initiative_components/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/ListComponentsCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_open_service_gateway_initiative_components/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/ListComponentsHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/ListComponentsHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListComponentsScenario.java
  - interop/scenarios/list-open-service-gateway-initiative-components.toml
status: done
merged_as: ""
---
# List Components

A bundle that is active and a component that is satisfied are different questions, and the second is the one that explains why a feature is not working. The unsatisfied references are the answer, and the component's own configuration values are not part of it.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListComponentsCommand` with a filter over component names and states, a result window, and an optional continuation token.
3. Implement `ListComponentsResult` as each component's name, its declaring bundle, its state, and — where it is unsatisfied — the references the platform reports as unsatisfied, with no configuration property of any kind.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `component_inventory_failed`. A component inventory that cannot be read is a refusal rather than an empty page, for the same reason a bundle inventory is.
5. Implement `ListComponentsHandler` reading the platform's own component inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- An unsatisfied component reports its unsatisfied references, proved against a fixture component with a missing dependency.
- No result carries a configuration property, asserted over the result type and over components configured with distinctive values.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_open_service_gateway_initiative_components` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_open_service_gateway_initiative_components` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListComponentsCommandTest && ./mvnw verify -pl aem -Dtest=ListComponentsHandlerTest && ./mvnw verify -pl interop -Dtest=ListComponentsScenario` proves unsatisfied references reported for an unsatisfied component, no configuration property reachable in the type or the response, and stable ordering across pages, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
