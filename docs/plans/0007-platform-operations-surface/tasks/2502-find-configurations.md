---
id: find-configurations
title: "Find Configurations"
workstream: "0025"
kind: task
depends_on:
  - platform-control-boundary
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/FindConfigurationsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/FindConfigurationsResult.java
  - core/src/main/resources/registry/find_open_service_gateway_initiative_configurations.toml
  - "schemas/commands/find_open_service_gateway_initiative_configurations/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/FindConfigurationsCommandTest.java
  - "core/src/test/resources/fixtures/commands/find_open_service_gateway_initiative_configurations/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/FindConfigurationsHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/FindConfigurationsHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/FindConfigurationsScenario.java
  - interop/scenarios/find-open-service-gateway-initiative-configurations.toml
status: done
merged_as: ""
---
# Find Configurations

An operator asking what is configured is usually trying to find out where a setting lives. Answering with identifiers and never with values is what makes this command safe to run in an environment whose configuration holds the deployment's credentials.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindConfigurationsCommand` with a filter over identifiers and declaring bundles, a result window, and an optional continuation token, with no filter over values.
3. Implement `FindConfigurationsResult` as each configuration's identifier, its factory identifier where it has one, and the bundle that declared it — and no value of any kind.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `configuration_lookup_failed`, `configuration_lookup_budget_exceeded`. A configuration inventory that cannot be read is a refusal rather than an empty page, because an empty list of configurations reads as a platform with none.
5. Implement `FindConfigurationsHandler` reading the platform's own configuration inventory after the permitted-group check, with no repository session involved at all.

**Tests:**

- The result carries no value member, asserted over the result type and over a platform whose configurations hold distinctive values that must not appear.
- A filter that would select on a value is refused at construction rather than ignored.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_open_service_gateway_initiative_configurations` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_open_service_gateway_initiative_configurations` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindConfigurationsCommandTest && ./mvnw verify -pl aem -Dtest=FindConfigurationsHandlerTest && ./mvnw verify -pl interop -Dtest=FindConfigurationsScenario` proves identifiers and declaring bundles with no value member reachable in the type or the response, and a value-selecting filter refused at construction, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
