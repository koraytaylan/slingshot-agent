---
id: set-workflow-instance-suspension
title: "Suspend or Resume a Workflow Instance"
workstream: "0026"
kind: task
depends_on:
  - terminate-workflow-instance
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/workflow/SetWorkflowSuspensionCommand.java
  - core/src/main/java/rs/slingshot/agent/command/workflow/SetWorkflowSuspensionResult.java
  - core/src/main/resources/registry/set_workflow_instance_suspension.toml
  - "schemas/commands/set_workflow_instance_suspension/**"
  - core/src/test/java/rs/slingshot/agent/command/workflow/SetWorkflowSuspensionCommandTest.java
  - "core/src/test/resources/fixtures/commands/set_workflow_instance_suspension/**"
  - aem/src/main/java/rs/slingshot/agent/aem/workflow/SetWorkflowSuspensionHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/workflow/SetWorkflowSuspensionHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/SetWorkflowSuspensionScenario.java
  - interop/scenarios/set-workflow-instance-suspension.toml
status: done
merged_as: ""
---
# Suspend or Resume a Workflow Instance

The reversible version of terminating, and the one an operator should reach for first. Making suspension and resumption one command with a desired state rather than two commands is what stops a caller resuming something that was never suspended.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `SetWorkflowSuspensionCommand` with the instance identifier, a required `SuspensionState`, and a required expected prior state of the same type, so the request and the guard are read in the same vocabulary.
3. Implement `SetWorkflowSuspensionResult` as the instance identifier and the `SuspensionState` the platform reports afterwards, read back in the same type the request used.
4. Declare exactly `instance_not_found`, `instance_access_denied`, `instance_not_suspendable`, `platform_control_rejected`, `platform_control_outcome_unknown`. `instance_not_suspendable` covers an instance the platform will not suspend from its current state, and is distinct from an expected-prior-state mismatch, which means the caller was looking at something stale.
5. Implement `SetWorkflowSuspensionHandler` comparing the expected prior state, applying through the platform's own interface, and reading the resulting state back.

**Tests:**

- Resuming an instance that was never suspended is refused rather than reported as resumed, with the instance asserted unchanged.
- Suspending and resuming the same instance returns it to its original state, proved by comparing the platform's report before and after.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`set_workflow_instance_suspension` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `set_workflow_instance_suspension` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=SetWorkflowSuspensionCommandTest && ./mvnw verify -pl aem -Dtest=SetWorkflowSuspensionHandlerTest && ./mvnw verify -pl interop -Dtest=SetWorkflowSuspensionScenario` proves a resume of a never-suspended instance refused with the instance unchanged, a suspend-and-resume round trip returning the platform's original state, and a required expected prior state, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
