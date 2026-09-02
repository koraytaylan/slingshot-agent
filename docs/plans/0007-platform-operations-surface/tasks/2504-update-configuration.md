---
id: update-configuration
title: "Update a Configuration"
workstream: "0025"
kind: task
depends_on:
  - inspect-configuration
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/UpdateConfigurationCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/UpdateConfigurationResult.java
  - core/src/main/resources/registry/update_open_service_gateway_initiative_configuration.toml
  - "schemas/commands/update_open_service_gateway_initiative_configuration/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/UpdateConfigurationCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_open_service_gateway_initiative_configuration/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/UpdateConfigurationHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/UpdateConfigurationHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdateConfigurationScenario.java
  - interop/scenarios/update-open-service-gateway-initiative-configuration.toml
status: done
merged_as: ""
---
# Update a Configuration

The command most likely to be run in an environment that will silently discard it. Refusing on a deployment row whose configuration is immutable, before anything is attempted, is the whole point of the capability boundary existing.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdateConfigurationCommand` with the configuration identifier, the exact instance for a factory configuration, and a `PropertyChange` using Plan 0006's shared type.
3. Implement `UpdateConfigurationResult` as the identifier and the property names actually changed — names only, never values, because echoing a value back is publishing one.
4. Declare exactly `configuration_lookup_failed`, `configuration_lookup_mismatch`, `configuration_lookup_ambiguous`, `configuration_value_unsupported`, `configuration_value_malformed`, `platform_control_rejected`, `platform_control_outcome_unknown`. `platform_control_rejected` covers both the platform saying no and the deployment row not providing the control, and the refusal names which of the two it was.
5. Implement `UpdateConfigurationHandler` checking the capability boundary first, then applying through the platform's own configuration interface after the permitted-group check.

**Tests:**

- On a deployment row whose configuration is immutable, the command is refused before the platform is touched, proved by a platform interface that would record any call.
- The result carries property names and no values, asserted over the result type and over an update whose values are distinctive.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_open_service_gateway_initiative_configuration` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_open_service_gateway_initiative_configuration` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdateConfigurationCommandTest && ./mvnw verify -pl aem -Dtest=UpdateConfigurationHandlerTest && ./mvnw verify -pl interop -Dtest=UpdateConfigurationScenario` proves a refusal before the platform is touched on an immutable row with the reason distinguishable from a platform rejection, names-only results with no value echoed, and a reachable distinct unknown outcome, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
