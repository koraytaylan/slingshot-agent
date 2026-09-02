---
id: inspect-configuration
title: "Inspect a Configuration"
workstream: "0025"
kind: task
depends_on:
  - find-configurations
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/InspectConfigurationCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/InspectConfigurationResult.java
  - core/src/main/resources/registry/inspect_open_service_gateway_initiative_configuration.toml
  - "schemas/commands/inspect_open_service_gateway_initiative_configuration/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/InspectConfigurationCommandTest.java
  - "core/src/test/resources/fixtures/commands/inspect_open_service_gateway_initiative_configuration/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/InspectConfigurationHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/InspectConfigurationHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/InspectConfigurationScenario.java
  - interop/scenarios/inspect-open-service-gateway-initiative-configuration.toml
status: done
merged_as: ""
---
# Inspect a Configuration

The one command that reports configuration values, and therefore the one where the two-phase rule earns its keep. Which properties exist is a question with a safe answer; what they hold is a question with a safe answer only where the platform's own metatype says so.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `InspectConfigurationCommand` with the configuration identifier and, for a factory configuration, the exact instance, refusing an identifier that matches several rather than choosing one.
3. Implement `InspectConfigurationResult` as each property's name, declared type, and either its value or an explicit withheld marker, using the shared `ValueDisclosure` rule.
4. Declare exactly `configuration_lookup_failed`, `configuration_lookup_mismatch`, `configuration_lookup_ambiguous`, `configuration_lookup_budget_exceeded`, `configuration_value_unsupported`, `configuration_value_malformed`, `configuration_value_budget_exceeded`, `configuration_result_budget_exceeded`. `configuration_value_unsupported` and `configuration_value_malformed` are distinct because a type this build cannot represent and a stored value that contradicts its declared type are different problems, and neither may be flattened into a string.
5. Implement `InspectConfigurationHandler` acquiring properties in the first phase and converting and disclosing in the second, never combining the two, after the permitted-group check.

**Tests:**

- A property the metatype marks as a secret is withheld, and a property with no metatype description is withheld, both with no value member.
- An identifier matching several instances is refused as ambiguous rather than answered from one, and the refusal names how many matched without naming them.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`inspect_open_service_gateway_initiative_configuration` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `inspect_open_service_gateway_initiative_configuration` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=InspectConfigurationCommandTest && ./mvnw verify -pl aem -Dtest=InspectConfigurationHandlerTest && ./mvnw verify -pl interop -Dtest=InspectConfigurationScenario` proves a two-phase inspection where secret and undescribed properties are withheld with no value member, an ambiguous identifier refused with a count and no names, and distinct unsupported-type and malformed-value refusals, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
