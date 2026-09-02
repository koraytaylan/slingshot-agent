---
id: move-asset
title: "Move an Asset"
workstream: "0022"
kind: task
depends_on:
  - delete-asset
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/asset/MoveAssetCommand.java
  - core/src/main/java/rs/slingshot/agent/command/asset/MoveAssetResult.java
  - core/src/main/resources/registry/move_asset.toml
  - "schemas/commands/move_asset/**"
  - core/src/test/java/rs/slingshot/agent/command/asset/MoveAssetCommandTest.java
  - "core/src/test/resources/fixtures/commands/move_asset/**"
  - aem/src/main/java/rs/slingshot/agent/aem/asset/MoveAssetHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/asset/MoveAssetHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/MoveAssetScenario.java
  - interop/scenarios/move-asset.toml
status: done
merged_as: ""
---
# Move an Asset

The same broken-link problem a page move has, with the difference that an asset is referenced from markup as often as from a property. Sharing the adjustment machinery with the page move is what stops the two drifting into different ideas of what a reference is.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `MoveAssetCommand` with a source address, a destination address, and a required reference-adjustment budget, using the same budget type the page move uses.
3. Implement `MoveAssetResult` as the new address and the number of references actually adjusted.
4. Declare exactly `source_not_found`, `source_access_denied`, `destination_parent_not_found`, `destination_already_exists`, `destination_inside_source`, `reference_adjustment_budget_exceeded`, `repository_commit_failed`, `mutation_outcome_unknown`. A destination inside the source is its own refusal for the same reason it is on a page move: it is trivially detectable and produces the most confusing repository state.
5. Implement `MoveAssetHandler` sharing the page move's reference-adjustment machinery rather than reimplementing it, counting before moving and refusing over budget before the commit.

**Tests:**

- The reference-adjustment machinery is asserted to be the very code the page move uses, not a copy.
- An over-budget move refuses before the commit with the asset asserted still at its original address and every reference unchanged.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`move_asset` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `move_asset` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=MoveAssetCommandTest && ./mvnw verify -pl aem -Dtest=MoveAssetHandlerTest && ./mvnw verify -pl interop -Dtest=MoveAssetScenario` proves shared rather than duplicated reference-adjustment machinery, an over-budget move refused before the commit with source and references untouched, and destination-inside-source refused, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
