---
id: delete-experience-fragment
title: "Delete an Experience Fragment"
workstream: "0023"
kind: task
depends_on:
  - update-experience-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/DeleteExperienceFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/DeleteExperienceFragmentResult.java
  - core/src/main/resources/registry/delete_experience_fragment.toml
  - "schemas/commands/delete_experience_fragment/**"
  - core/src/test/java/rs/slingshot/agent/command/fragment/DeleteExperienceFragmentCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_experience_fragment/**"
  - aem/src/main/java/rs/slingshot/agent/aem/fragment/DeleteExperienceFragmentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/fragment/DeleteExperienceFragmentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeleteExperienceFragmentScenario.java
  - interop/scenarios/delete-experience-fragment.toml
status: done
merged_as: ""
---
# Delete an Experience Fragment

An experience fragment is referenced from the pages that embed it and, increasingly, from channels outside the repository entirely. The policy covers what can be seen, and the result says how much went, which is the most this side can honestly offer.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeleteExperienceFragmentCommand` with the fragment address, a required `ReferencePolicy`, and a required `DeletionBudget`.
3. Implement `DeleteExperienceFragmentResult` as the shared `DeletedResourceResult`, with the removed-node count covering every variation.
4. Declare exactly `fragment_not_found`, `fragment_access_denied`, `fragment_invalid`, `fragment_is_referenced`, `deletion_budget_exceeded`, `repository_commit_failed`, `mutation_outcome_unknown`. The refusing policy covers references this repository can observe, and the result documents plainly that a reference from outside the repository is not among them.
5. Implement `DeleteExperienceFragmentHandler` counting every variation against the budget before removing anything, in one commit under the caller's session.

**Tests:**

- The removed-node count covers every variation, proved against a fragment with several.
- The type documents that unobservable external references are outside the policy, and the documentation check asserts that statement exists rather than inferring it.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_experience_fragment` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_experience_fragment` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeleteExperienceFragmentCommandTest && ./mvnw verify -pl aem -Dtest=DeleteExperienceFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=DeleteExperienceFragmentScenario` proves a removed count covering every variation, references stopping a refusing delete with the addresses reported, and a stated limit on what the policy can observe, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
