---
id: move-page
title: "Move a Page"
workstream: "0020"
kind: task
depends_on:
  - delete-page
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/page/MovePageCommand.java
  - core/src/main/java/rs/slingshot/agent/command/page/MovePageResult.java
  - core/src/main/resources/registry/move_page.toml
  - "schemas/commands/move_page/**"
  - core/src/test/java/rs/slingshot/agent/command/page/MovePageCommandTest.java
  - "core/src/test/resources/fixtures/commands/move_page/**"
  - aem/src/main/java/rs/slingshot/agent/aem/page/MovePageHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/page/MovePageHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/MovePageScenario.java
  - interop/scenarios/move-page.toml
status: done
merged_as: ""
---
# Move a Page

Moving is where links break, and leaving them broken is not an option a caller would knowingly take. Adjusting an unbounded number of them is how one move rewrites a repository, so the adjustment has a budget and the budget refuses before the commit rather than after half of it.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `MovePageCommand` with a source address, a destination address, and a required reference-adjustment budget.
3. Implement `MovePageResult` as the new address and the number of references actually adjusted, so a caller knows the scale of what just happened.
4. Declare exactly `source_not_found`, `source_access_denied`, `destination_parent_not_found`, `destination_already_exists`, `destination_inside_source`, `reference_adjustment_budget_exceeded`, `repository_commit_failed`, `mutation_outcome_unknown`. A destination inside the source is its own refusal because it is the mistake that produces the most confusing repository state and the one a path comparison catches trivially.
5. Implement `MovePageHandler` counting the references that would need adjusting before moving anything, refusing over budget before the commit, and performing the move and every adjustment in one commit.

**Tests:**

- A move whose reference adjustments exceed the budget refuses before the commit, with the source asserted still at its original address and every reference unchanged.
- A destination inside the source subtree is refused, and so is a destination equal to the source.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`move_page` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `move_page` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=MovePageCommandTest && ./mvnw verify -pl aem -Dtest=MovePageHandlerTest && ./mvnw verify -pl interop -Dtest=MovePageScenario` proves a move reporting its actual new address and adjustment count, an over-budget move refused before the commit with source and references untouched, and destination-inside-source and destination-equals-source both refused, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
