---
id: set-user-disabled
title: "Disable or Enable a User"
workstream: "0028"
kind: task
depends_on:
  - update-user-profile
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/authorizable/SetUserDisabledCommand.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/SetUserDisabledResult.java
  - core/src/main/resources/registry/set_user_disabled.toml
  - "schemas/commands/set_user_disabled/**"
  - core/src/test/java/rs/slingshot/agent/command/authorizable/SetUserDisabledCommandTest.java
  - "core/src/test/resources/fixtures/commands/set_user_disabled/**"
  - aem/src/main/java/rs/slingshot/agent/aem/authorizable/SetUserDisabledHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/authorizable/SetUserDisabledHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/SetUserDisabledScenario.java
  - interop/scenarios/set-user-disabled.toml
status: done
merged_as: ""
---
# Disable or Enable a User

The reversible answer to an account that should stop working, and the one an operator should reach for before deleting anything. One command with a desired state rather than two stops a caller enabling an account that was never disabled and believing they fixed something.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `SetUserDisabledCommand` with the identifier, a required `AccountState`, and the reason where the platform records one — a named state rather than a boolean, because `set(true)` at a call site is ambiguous about which way it points.
3. Implement `SetUserDisabledResult` as the identifier and the `AccountState` the platform reports afterwards, read back rather than assumed.
4. Declare exactly `authorizable_not_found`, `authorizable_kind_mismatch`, `authorizable_access_denied`, `platform_control_rejected`, `platform_control_outcome_unknown`. `authorizable_kind_mismatch` covers a group, which cannot be disabled, and is reported as itself rather than as a platform rejection.
5. Implement `SetUserDisabledHandler` applying through the platform's own user management under the caller's session and reading the resulting state back.

**Tests:**

- Disabling and re-enabling returns the account to its original state, proved by comparing the platform's report before and after.
- Disabling a group is refused as a kind mismatch rather than attempted, with the group asserted unchanged.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`set_user_disabled` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `set_user_disabled` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=SetUserDisabledCommandTest && ./mvnw verify -pl aem -Dtest=SetUserDisabledHandlerTest && ./mvnw verify -pl interop -Dtest=SetUserDisabledScenario` proves a disable-and-enable round trip returning the platform's original state, a group refused as a kind mismatch with nothing attempted, and a state read back rather than assumed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
