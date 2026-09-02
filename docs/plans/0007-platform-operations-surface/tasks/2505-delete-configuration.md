---
id: delete-configuration
title: "Delete a Configuration"
workstream: "0025"
kind: task
depends_on:
  - update-configuration
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/DeleteConfigurationCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/DeleteConfigurationResult.java
  - core/src/main/resources/registry/delete_open_service_gateway_initiative_configuration.toml
  - "schemas/commands/delete_open_service_gateway_initiative_configuration/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/DeleteConfigurationCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_open_service_gateway_initiative_configuration/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/DeleteConfigurationHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/DeleteConfigurationHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeleteConfigurationScenario.java
  - interop/scenarios/delete-open-service-gateway-initiative-configuration.toml
status: done
merged_as: ""
---
# Delete a Configuration

Deleting a configuration is how a service reverts to its declared defaults, which is sometimes exactly what an operator wants and is never what they want by accident. The ambiguity refusal matters more here than on inspection, because the wrong instance is unrecoverable.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeleteConfigurationCommand` with the configuration identifier and the exact instance for a factory configuration, refusing an identifier matching several.
3. Implement `DeleteConfigurationResult` as the identifier removed and whether it was a factory instance, so the caller can tell which of two similar things went.
4. Declare exactly `configuration_lookup_failed`, `configuration_lookup_mismatch`, `configuration_lookup_ambiguous`, `platform_control_rejected`, `platform_control_outcome_unknown`. `configuration_lookup_mismatch` is distinct from `configuration_lookup_ambiguous`: the first means the caller named something that is not what they think, the second means they named too little.
5. Implement `DeleteConfigurationHandler` checking the capability boundary first and removing through the platform's own interface after the permitted-group check.

**Tests:**

- An ambiguous identifier is refused with nothing removed, proved by asserting the platform's configuration inventory is unchanged.
- On an immutable deployment row the command is refused before the platform is touched.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_open_service_gateway_initiative_configuration` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_open_service_gateway_initiative_configuration` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeleteConfigurationCommandTest && ./mvnw verify -pl aem -Dtest=DeleteConfigurationHandlerTest && ./mvnw verify -pl interop -Dtest=DeleteConfigurationScenario` proves an ambiguous identifier refused with the inventory unchanged, distinct mismatch and ambiguity refusals, and a pre-platform refusal on an immutable row, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
