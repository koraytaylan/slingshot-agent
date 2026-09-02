---
id: set-bundle-state
title: "Set a Bundle State"
workstream: "0025"
kind: task
depends_on:
  - list-bundles
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/platform/SetBundleStateCommand.java
  - core/src/main/java/rs/slingshot/agent/command/platform/SetBundleStateResult.java
  - core/src/main/resources/registry/set_open_service_gateway_initiative_bundle_state.toml
  - "schemas/commands/set_open_service_gateway_initiative_bundle_state/**"
  - core/src/test/java/rs/slingshot/agent/command/platform/SetBundleStateCommandTest.java
  - "core/src/test/resources/fixtures/commands/set_open_service_gateway_initiative_bundle_state/**"
  - aem/src/main/java/rs/slingshot/agent/aem/platform/SetBundleStateHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/platform/SetBundleStateHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/SetBundleStateScenario.java
  - interop/scenarios/set-open-service-gateway-initiative-bundle-state.toml
status: done
merged_as: ""
---
# Set a Bundle State

A bundle stopped through a running platform comes back on the next deployment, and the interval in between is an environment nobody can reason about. Where the deployment does permit it, the expected prior state stops a transition applying to a platform the caller was not looking at.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `SetBundleStateCommand` with the symbolic name, the desired state, and a required expected prior state, using Plan 0006's guard vocabulary rather than a second version of it.
3. Implement `SetBundleStateResult` as the symbolic name and the state the platform reports afterwards, read back rather than assumed from the request.
4. Declare exactly `bundle_not_found`, `bundle_transition_refused`, `platform_control_rejected`, `platform_control_outcome_unknown`. `bundle_transition_refused` covers a transition the platform will not make from the state it is in, which is different from the deployment not permitting the control at all.
5. Implement `SetBundleStateHandler` checking the capability boundary first, comparing the expected prior state, and reading the resulting state back from the platform.

**Tests:**

- A transition whose expected prior state does not match is refused with the bundle asserted in its original state.
- The reported state is read back from the platform rather than echoed from the request, proved by a fixture platform that reports a different state.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`set_open_service_gateway_initiative_bundle_state` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `set_open_service_gateway_initiative_bundle_state` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=SetBundleStateCommandTest && ./mvnw verify -pl aem -Dtest=SetBundleStateHandlerTest && ./mvnw verify -pl interop -Dtest=SetBundleStateScenario` proves a required expected prior state refusing a mismatched transition with the bundle unchanged, a state read back rather than echoed, and a pre-platform refusal on a row that does not permit the control, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
