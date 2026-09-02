---
id: delete-component
title: "Delete a Component"
workstream: "0021"
kind: task
depends_on:
  - update-component
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/component/DeleteComponentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/component/DeleteComponentResult.java
  - core/src/main/resources/registry/delete_component.toml
  - "schemas/commands/delete_component/**"
  - core/src/test/java/rs/slingshot/agent/command/component/DeleteComponentCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_component/**"
  - aem/src/main/java/rs/slingshot/agent/aem/component/DeleteComponentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/component/DeleteComponentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeleteComponentScenario.java
  - interop/scenarios/delete-component.toml
status: done
merged_as: ""
---
# Delete a Component

Removing a component is the one destructive content operation with no reference policy, because a component is not referenced the way a page or an asset is. Saying that out loud is better than a caller wondering why the guard they expected is missing.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeleteComponentCommand` with the component's address and nothing else, and document on the type why no reference policy applies here.
3. Implement `DeleteComponentResult` as the removed address and the removed-node count, using the shared deleted-resource shape.
4. Declare exactly `component_not_found`, `component_access_denied`, `component_invalid`, `repository_commit_failed`, `mutation_outcome_unknown`. An address that exists and is not a component is refused rather than removed, because removing a container by mistake removes everything in it.
5. Implement `DeleteComponentHandler` removing the component's subtree in one commit under the caller's session.

**Tests:**

- Removing a component leaves its siblings and their order unchanged, proved by reading the container back.
- An address that resolves to a page or a container is refused, and the subtree is asserted byte-identical afterwards.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_component` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_component` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeleteComponentCommandTest && ./mvnw verify -pl aem -Dtest=DeleteComponentHandlerTest && ./mvnw verify -pl interop -Dtest=DeleteComponentScenario` proves siblings and their order unchanged after a removal, and a page or container address refused with the subtree byte-identical, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
