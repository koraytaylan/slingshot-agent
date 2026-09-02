---
id: platform-control-boundary
title: "Platform Control Boundary"
workstream: "0025"
kind: task
depends_on: []
gated: false
touches:
  - support/deployments.toml
  - core/src/main/java/rs/slingshot/agent/command/platform/PlatformControl.java
  - core/src/main/java/rs/slingshot/agent/command/platform/ControlCapability.java
  - core/src/main/java/rs/slingshot/agent/command/platform/ValueDisclosure.java
  - core/src/main/java/rs/slingshot/agent/command/platform/SuspensionState.java
  - core/src/main/java/rs/slingshot/agent/command/platform/AccountState.java
  - core/src/main/java/rs/slingshot/agent/command/platform/package-info.java
  - core/src/test/java/rs/slingshot/agent/command/platform/PlatformControlTest.java
  - "core/src/test/resources/fixtures/platform-control/**"
  - development/src/main/java/rs/slingshot/agent/development/ControlCapabilityCoverage.java
  - development/src/test/java/rs/slingshot/agent/development/ControlCapabilityCoverageTest.java
status: done
merged_as: ""
---
# Platform Control Boundary

An Adobe Experience Manager as a Cloud Service environment has immutable configuration and bundle lifecycle. A change written through the running platform is not persisted, is undone by the next deployment, and leaves an operator believing they changed something they did not. Performing it and reporting success is worse than refusing, because their next action depends on believing the answer.

**Steps:**

1. Author fixtures for a control permitted on a row, refused on a row, a command declaring a capability no row names, and a row naming a capability no command uses.
2. Extend `support/deployments.toml` with a capability list per row: which platform controls that deployment actually provides, with a reason for each absence rather than a bare omission.
3. Implement `ControlCapability` as the closed set of controls, and `PlatformControl` as the gate every control command passes through before it does anything at all, refusing with the deployment row named.
4. Implement `ValueDisclosure` as the two-phase rule every configuration command uses: which properties exist is one question, what they hold is another, and a value is reported only where the platform's own metatype says the property is not a secret.
5. Make withheld different from masked and from absent: a withheld property is present with no value member, because a masked value is still a value with a length somebody will compare.
6. Implement the two-valued platform states more than one command in this plan reads or writes — `SuspensionState` for a workflow instance and a job queue alike, and `AccountState` — as named types here rather than inside whichever command happened to need one first, and take the guard that compares them from Plan 0006's `StateExpectation` rather than writing a second one. A state a caller can request and a state the platform reports afterwards are the same type, which is what lets a result be compared against a request without a conversion nobody wrote down.

**Tests:**

- A control permitted on a row proceeds; one refused on a row is refused before the handler runs, proved by a handler that would fail if reached.
- Every declared capability is used by at least one command and every control command declares a capability, in both directions.
- A capability absent from a row without a reason is rejected.
- A withheld property is asserted to carry no value member at all, distinct from an absent property and from a masked one, across the whole disclosure fixture set.
- A property with no metatype description is withheld rather than published, and a fixture publishing one is rejected.
- Each platform state type is asserted two-valued with no default and no boolean conversion, and the guard comparing one is asserted to be Plan 0006's own type rather than a copy.

- **Done when:** `./mvnw verify -pl core -Dtest=PlatformControlTest && ./mvnw verify -pl development -Dtest=ControlCapabilityCoverageTest` proves a control refused before its handler runs on a row that does not provide it, two-way capability-to-command correspondence with reasons required for every absence, a withheld property distinct from absent and from masked including for an undescribed property, and named two-valued platform states with no default whose guard is Plan 0006's own type rather than a copy.
