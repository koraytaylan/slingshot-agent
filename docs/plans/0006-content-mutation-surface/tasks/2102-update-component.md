---
id: update-component
title: "Update a Component"
workstream: "0021"
kind: task
depends_on:
  - add-component
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/component/UpdateComponentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/component/UpdateComponentResult.java
  - core/src/main/resources/registry/update_component.toml
  - "schemas/commands/update_component/**"
  - core/src/test/java/rs/slingshot/agent/command/component/UpdateComponentCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_component/**"
  - aem/src/main/java/rs/slingshot/agent/aem/component/UpdateComponentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/component/UpdateComponentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdateComponentScenario.java
  - interop/scenarios/update-component.toml
status: done
merged_as: ""
---
# Update a Component

The same two-list update a page gets, one level down, where the properties are a component's own and a protected one is far more likely. It shares the vocabulary rather than restating it, so the absent-property rule cannot drift between the two.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdateComponentCommand` with the component's address and a `PropertyChange`, using the same shared type the page update uses rather than a second one.
3. Implement `UpdateComponentResult` as the address, the properties actually set, and the properties actually removed.
4. Declare exactly `component_not_found`, `component_access_denied`, `component_invalid`, `property_rejected`, `property_not_removable`, `repository_commit_failed`, `mutation_outcome_unknown`. An address that exists and is not a component is distinct from one that does not exist, because the first usually means the caller addressed a container.
5. Implement `UpdateComponentHandler` applying both lists in one commit under the caller's session, refusing before the commit if any named property cannot be removed.

**Tests:**

- The property-change type is asserted to be the very type the page update uses, not a copy.
- Updating a container rather than a component is refused as not-a-component, and the container is asserted unchanged.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_component` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_component` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdateComponentCommandTest && ./mvnw verify -pl aem -Dtest=UpdateComponentHandlerTest && ./mvnw verify -pl interop -Dtest=UpdateComponentScenario` proves a shared rather than duplicated property-change type, an absent property left unchanged, a protected removal refused before the commit, and a container address refused as not-a-component, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
