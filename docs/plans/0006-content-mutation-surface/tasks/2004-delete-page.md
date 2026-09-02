---
id: delete-page
title: "Delete a Page"
workstream: "0020"
kind: task
depends_on:
  - update-page
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/page/DeletePageCommand.java
  - core/src/main/java/rs/slingshot/agent/command/page/DeletePageResult.java
  - core/src/main/resources/registry/delete_page.toml
  - "schemas/commands/delete_page/**"
  - core/src/test/java/rs/slingshot/agent/command/page/DeletePageCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_page/**"
  - aem/src/main/java/rs/slingshot/agent/aem/page/DeletePageHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/page/DeletePageHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeletePageScenario.java
  - interop/scenarios/delete-page.toml
status: done
merged_as: ""
---
# Delete a Page

The operation an operator most wants a guard on, and the one where the guard cannot be chosen for them. Deleting a page other pages reference is sometimes exactly right and sometimes catastrophic, and this side has no way to tell which.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeletePageCommand` with a page address, a required `ReferencePolicy`, and a required `DeletionBudget`.
3. Implement `DeletePageResult` as the shared `DeletedResourceResult`: the removed address and a removed-node count bounded by the budget.
4. Declare exactly `target_not_found`, `target_access_denied`, `target_not_a_page`, `target_is_referenced`, `deletion_budget_exceeded`, `repository_commit_failed`, `mutation_outcome_unknown`. An absent target is a failure rather than a success with nothing to do, because a caller who mistyped an address and was told the delete succeeded will believe something is gone that is not.
5. Implement `DeletePageHandler` counting the subtree against the budget before removing anything, and under the refusing policy collecting the references that stopped it, bounded, so the caller can decide rather than guess.

**Tests:**

- `target_is_referenced` is reachable only under the refusing policy, and the fixture inventory proves both policies appear across the vector set.
- An over-budget subtree refuses with nothing removed, proved by comparing the subtree before and after.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_page` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_page` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeletePageCommandTest && ./mvnw verify -pl aem -Dtest=DeletePageHandlerTest && ./mvnw verify -pl interop -Dtest=DeletePageScenario` proves a required reference policy with the referenced refusal reachable only under refusal and both policies present in the fixtures, an over-budget delete that removes nothing, and an absent target refused rather than reported done, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
