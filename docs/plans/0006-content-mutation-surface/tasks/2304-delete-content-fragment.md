---
id: delete-content-fragment
title: "Delete a Content Fragment"
workstream: "0023"
kind: task
depends_on:
  - update-content-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/DeleteContentFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/DeleteContentFragmentResult.java
  - core/src/main/resources/registry/delete_content_fragment.toml
  - "schemas/commands/delete_content_fragment/**"
  - core/src/test/java/rs/slingshot/agent/command/fragment/DeleteContentFragmentCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_content_fragment/**"
  - aem/src/main/java/rs/slingshot/agent/aem/fragment/DeleteContentFragmentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/fragment/DeleteContentFragmentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeleteContentFragmentScenario.java
  - interop/scenarios/delete-content-fragment.toml
status: done
merged_as: ""
---
# Delete a Content Fragment

Fragments are referenced by the pages that render them and by other fragments, which makes the reference policy do more work here than anywhere else. Deleting the whole fragment removes every variation, and saying so in the count is what stops that being a surprise.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeleteContentFragmentCommand` with the fragment address, a required `ReferencePolicy`, and a required `DeletionBudget`.
3. Implement `DeleteContentFragmentResult` as the shared `DeletedResourceResult`, with the removed-node count covering every variation.
4. Declare exactly `fragment_not_found`, `fragment_access_denied`, `fragment_invalid`, `fragment_is_referenced`, `deletion_budget_exceeded`, `repository_commit_failed`, `mutation_outcome_unknown`. `fragment_is_referenced` is reachable only under the refusing policy and carries the referencing addresses, bounded, including references from other fragments rather than only from pages.
5. Implement `DeleteContentFragmentHandler` counting every variation against the budget before removing anything, in one commit under the caller's session.

**Tests:**

- The removed-node count covers every variation, proved against a fragment with several.
- A reference from another fragment stops the delete under the refusing policy just as a reference from a page does, and both appear in the reported addresses.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_content_fragment` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_content_fragment` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeleteContentFragmentCommandTest && ./mvnw verify -pl aem -Dtest=DeleteContentFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=DeleteContentFragmentScenario` proves a removed count covering every variation, fragment-to-fragment references stopping a refusing delete alongside page references with both reported, and both sides of the deletion budget, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
