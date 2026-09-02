---
id: delete-asset
title: "Delete an Asset"
workstream: "0022"
kind: task
depends_on:
  - update-asset-metadata
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/asset/DeleteAssetCommand.java
  - core/src/main/java/rs/slingshot/agent/command/asset/DeleteAssetResult.java
  - core/src/main/resources/registry/delete_asset.toml
  - "schemas/commands/delete_asset/**"
  - core/src/test/java/rs/slingshot/agent/command/asset/DeleteAssetCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_asset/**"
  - aem/src/main/java/rs/slingshot/agent/aem/asset/DeleteAssetHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/asset/DeleteAssetHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeleteAssetScenario.java
  - interop/scenarios/delete-asset.toml
status: done
merged_as: ""
---
# Delete an Asset

An asset referenced by a page is the reference case operators meet most, because an asset outlives the page that used it and nobody remembers which pages those were. The policy is required for the same reason it is on a page delete, and the refusal has to say what stopped it.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeleteAssetCommand` with an asset address, a required `ReferencePolicy`, and a required `DeletionBudget`.
3. Implement `DeleteAssetResult` as the shared `DeletedResourceResult`: the removed address and a removed-node count including renditions.
4. Declare exactly `asset_not_found`, `asset_access_denied`, `asset_invalid`, `asset_is_referenced`, `deletion_budget_exceeded`, `repository_commit_failed`, `mutation_outcome_unknown`. `asset_is_referenced` is reachable only under the refusing policy, and the refusal carries the referencing addresses, bounded, so the caller can decide rather than guess.
5. Implement `DeleteAssetHandler` counting the asset and its renditions against the budget before removing anything, in one commit under the caller's session.

**Tests:**

- The removed-node count includes every rendition, proved against an asset with several.
- Under the refusing policy a referenced asset is refused with the referencing addresses reported, bounded, and the asset asserted byte-identical afterwards.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_asset` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_asset` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeleteAssetCommandTest && ./mvnw verify -pl aem -Dtest=DeleteAssetHandlerTest && ./mvnw verify -pl interop -Dtest=DeleteAssetScenario` proves a removed-node count including every rendition, a referenced asset refused under the refusing policy with bounded referencing addresses reported and the asset untouched, and both sides of the deletion budget, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
