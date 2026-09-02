---
id: add-component
title: "Add a Component"
workstream: "0021"
kind: task
depends_on:
  - move-page
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/component/AddComponentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/component/AddComponentResult.java
  - core/src/main/resources/registry/add_component.toml
  - "schemas/commands/add_component/**"
  - core/src/test/java/rs/slingshot/agent/command/component/AddComponentCommandTest.java
  - "core/src/test/resources/fixtures/commands/add_component/**"
  - aem/src/main/java/rs/slingshot/agent/aem/component/AddComponentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/component/AddComponentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/AddComponentScenario.java
  - interop/scenarios/add-component.toml
status: done
merged_as: ""
---
# Add a Component

A component's position on a page is part of what it means, and a parent that cannot hold an order is a parent where position is a lie. Reporting that as itself rather than as a generic refusal is what lets a caller understand a page they did not build.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `AddComponentCommand` with the page address, the container path within it, the component resource type, a name, an optional sibling to insert before, and the initial properties.
3. Implement `AddComponentResult` as the created component's address and its position among its siblings, so a caller sees where it actually landed.
4. Declare exactly `page_not_found`, `page_invalid`, `parent_not_found`, `parent_access_denied`, `parent_not_orderable`, `target_already_exists`, `property_rejected`, `repository_commit_failed`, `mutation_outcome_unknown`. `parent_not_orderable` is its own category because a caller who asked for a position in an unordered container needs to know their position was not merely ignored.
5. Implement `AddComponentHandler` creating the node and, where a sibling was named, ordering against it in the same commit, under the caller's session.

**Tests:**

- Inserting before a named sibling places the component exactly there, proved by reading the sibling order back.
- Requesting a position in an unorderable parent is refused as unorderable rather than silently appended, and the container is asserted unchanged.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`add_component` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `add_component` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=AddComponentCommandTest && ./mvnw verify -pl aem -Dtest=AddComponentHandlerTest && ./mvnw verify -pl interop -Dtest=AddComponentScenario` proves a component placed exactly before its named sibling with its actual position reported, and a position request in an unorderable parent refused as itself with the container unchanged, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
