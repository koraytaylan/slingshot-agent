---
id: terminate-workflow-instance
title: "Terminate a Workflow Instance"
workstream: "0026"
kind: task
depends_on:
  - inspect-workflow-instance
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/workflow/TerminateWorkflowInstanceCommand.java
  - core/src/main/java/rs/slingshot/agent/command/workflow/TerminateWorkflowInstanceResult.java
  - core/src/main/resources/registry/terminate_workflow_instance.toml
  - "schemas/commands/terminate_workflow_instance/**"
  - core/src/test/java/rs/slingshot/agent/command/workflow/TerminateWorkflowInstanceCommandTest.java
  - "core/src/test/resources/fixtures/commands/terminate_workflow_instance/**"
  - aem/src/main/java/rs/slingshot/agent/aem/workflow/TerminateWorkflowInstanceHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/workflow/TerminateWorkflowInstanceHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/TerminateWorkflowInstanceScenario.java
  - interop/scenarios/terminate-workflow-instance.toml
status: done
merged_as: ""
---
# Terminate a Workflow Instance

Ending a workflow leaves its payload in whatever state the last completed step left it, which is a thing an operator needs told rather than discovered. The result says what state the instance ended in, and says nothing about the payload, because it cannot honestly say anything.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `TerminateWorkflowInstanceCommand` with the instance identifier and a required expected prior state, using Plan 0006's guard vocabulary.
3. Implement `TerminateWorkflowInstanceResult` as the instance identifier and the state the platform reports afterwards, read back rather than assumed.
4. Declare exactly `instance_not_found`, `instance_access_denied`, `instance_not_terminable`, `platform_control_rejected`, `platform_control_outcome_unknown`. `instance_not_terminable` covers an instance the platform will not end from the state it is in, which is different from one that does not exist and different again from an unknown outcome.
5. Implement `TerminateWorkflowInstanceHandler` comparing the expected prior state, terminating through the platform's own interface, and reading the resulting state back.

**Tests:**

- An expected prior state that does not match refuses with the instance asserted still running.
- An already-terminated instance is refused as not terminable rather than reported as terminated again.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`terminate_workflow_instance` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `terminate_workflow_instance` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=TerminateWorkflowInstanceCommandTest && ./mvnw verify -pl aem -Dtest=TerminateWorkflowInstanceHandlerTest && ./mvnw verify -pl interop -Dtest=TerminateWorkflowInstanceScenario` proves a required expected prior state refusing a mismatched termination with the instance still running, an already-terminated instance refused rather than re-reported, and a state read back rather than assumed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
