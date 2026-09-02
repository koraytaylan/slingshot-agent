---
id: update-asset-metadata
title: "Update Asset Metadata"
workstream: "0022"
kind: task
depends_on:
  - create-asset
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/asset/UpdateAssetMetadataCommand.java
  - core/src/main/java/rs/slingshot/agent/command/asset/UpdateAssetMetadataResult.java
  - core/src/main/resources/registry/update_asset_metadata.toml
  - "schemas/commands/update_asset_metadata/**"
  - core/src/test/java/rs/slingshot/agent/command/asset/UpdateAssetMetadataCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_asset_metadata/**"
  - aem/src/main/java/rs/slingshot/agent/aem/asset/UpdateAssetMetadataHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/asset/UpdateAssetMetadataHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdateAssetMetadataScenario.java
  - interop/scenarios/update-asset-metadata.toml
status: done
merged_as: ""
---
# Update Asset Metadata

Asset metadata is where customers put arbitrary properties, and also where the platform puts ones it maintains itself. An update that removed a maintained property because a caller sent a partial view is the failure this command exists to make impossible.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdateAssetMetadataCommand` with the asset address and a `PropertyChange`, using the shared type.
3. Implement `UpdateAssetMetadataResult` as the address, the properties actually set, and the properties actually removed.
4. Declare exactly `asset_not_found`, `asset_access_denied`, `asset_invalid`, `property_rejected`, `property_not_removable`, `repository_commit_failed`, `mutation_outcome_unknown`. `property_not_removable` covers the platform-maintained properties, and the refusal names them so a caller can stop asking rather than retry.
5. Implement `UpdateAssetMetadataHandler` writing into the asset's own metadata node and nowhere else, in one commit under the caller's session.

**Tests:**

- A write is proved confined to the asset's metadata node, with the rest of the asset including its renditions asserted byte-identical.
- A platform-maintained property named for removal is refused before the commit, naming it, with every other property in the request unapplied.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_asset_metadata` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_asset_metadata` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdateAssetMetadataCommandTest && ./mvnw verify -pl aem -Dtest=UpdateAssetMetadataHandlerTest && ./mvnw verify -pl interop -Dtest=UpdateAssetMetadataScenario` proves a write confined to the metadata node with renditions untouched, and a platform-maintained removal refused before the commit with the rest unapplied, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
