---
id: list-bundles
title: "List Bundles"
workstream: "0025"
kind: task
depends_on:
  - delete-configuration
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/ListBundlesCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/ListBundlesResult.java
  - core/src/main/resources/registry/list_open_service_gateway_initiative_bundles.toml
  - "schemas/commands/list_open_service_gateway_initiative_bundles/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/ListBundlesCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_open_service_gateway_initiative_bundles/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/ListBundlesHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/ListBundlesHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListBundlesScenario.java
  - interop/scenarios/list-open-service-gateway-initiative-bundles.toml
status: done
merged_as: ""
---
# List Bundles

The first thing anybody looks at when a deployment misbehaves, and a read that is true on every deployment row. Reporting unresolved requirements rather than only a state is what turns it from a list into a diagnosis.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListBundlesCommand` with a filter over symbolic names and states, a result window, and an optional continuation token.
3. Implement `ListBundlesResult` as each bundle's symbolic name, version, state, and — where it is not resolved — the requirements the platform reports as unsatisfied.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `bundle_inventory_failed`. A bundle inventory that cannot be read is a refusal rather than an empty page, because an empty bundle list is a platform that could not exist.
5. Implement `ListBundlesHandler` reading the platform's own bundle inventory after the permitted-group check, in a stable order across pages.

**Tests:**

- An unresolved bundle reports its unsatisfied requirements, proved against a deliberately unresolvable fixture bundle.
- Ordering is stable across pages, proved by comparing concatenated pages against a single unbounded read.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_open_service_gateway_initiative_bundles` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_open_service_gateway_initiative_bundles` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListBundlesCommandTest && ./mvnw verify -pl aem -Dtest=ListBundlesHandlerTest && ./mvnw verify -pl interop -Dtest=ListBundlesScenario` proves unsatisfied requirements reported for an unresolved bundle, stable ordering across pages against an unbounded read, and a refused rather than empty answer when the inventory cannot be read, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
