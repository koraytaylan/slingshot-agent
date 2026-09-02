---
id: reorder-component
title: "Reorder a Component"
workstream: "0021"
kind: task
depends_on:
  - delete-component
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/component/ReorderComponentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/component/ReorderComponentResult.java
  - core/src/main/resources/registry/reorder_component.toml
  - "schemas/commands/reorder_component/**"
  - core/src/test/java/rs/slingshot/agent/command/component/ReorderComponentCommandTest.java
  - "core/src/test/resources/fixtures/commands/reorder_component/**"
  - aem/src/main/java/rs/slingshot/agent/aem/component/ReorderComponentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/component/ReorderComponentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ReorderComponentScenario.java
  - interop/scenarios/reorder-component.toml
status: done
merged_as: ""
---
# Reorder a Component

A position by index is a race with whoever else is editing the page. Naming the sibling this component should end up before turns the same request into something that either applies to the page the caller was looking at or refuses.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ReorderComponentCommand` with the component's address and the sibling to place it before, with a distinguished value for placing it last rather than representing that by absence.
3. Implement `ReorderComponentResult` as the address and the resulting sibling order, so the caller sees the whole arrangement rather than a claim about one node.
4. Declare exactly `component_not_found`, `component_access_denied`, `parent_not_orderable`, `sibling_not_found`, `repository_commit_failed`, `mutation_outcome_unknown`. `sibling_not_found` is distinct from `parent_not_orderable`: the first means the page changed under the caller, the second means the request never made sense.
5. Implement `ReorderComponentHandler` ordering against the named sibling in one commit under the caller's session, and refusing rather than appending when the sibling has gone.

**Tests:**

- A named sibling that has been removed since the caller read the page is refused rather than appended, and the order is asserted unchanged.
- Placing last uses the distinguished value, and a fixture representing it by absence is refused at construction.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`reorder_component` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `reorder_component` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ReorderComponentCommandTest && ./mvnw verify -pl aem -Dtest=ReorderComponentHandlerTest && ./mvnw verify -pl interop -Dtest=ReorderComponentScenario` proves an order reported in full, a vanished sibling refused rather than appended with the order unchanged, and last-position expressed by a distinguished value rather than absence, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
