---
id: create-asset-folder
title: "Create an Asset Folder"
workstream: "0022"
kind: task
depends_on:
  - reorder-component
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/asset/CreateAssetFolderCommand.java
  - core/src/main/java/rs/slingshot/agent/command/asset/CreateAssetFolderResult.java
  - core/src/main/resources/registry/create_asset_folder.toml
  - "schemas/commands/create_asset_folder/**"
  - core/src/test/java/rs/slingshot/agent/command/asset/CreateAssetFolderCommandTest.java
  - "core/src/test/resources/fixtures/commands/create_asset_folder/**"
  - aem/src/main/java/rs/slingshot/agent/aem/asset/CreateAssetFolderHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/asset/CreateAssetFolderHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CreateAssetFolderScenario.java
  - interop/scenarios/create-asset-folder.toml
status: done
merged_as: ""
---
# Create an Asset Folder

The smallest write in the surface, and therefore the one worth using to pin the shape the rest follow: one required parent, one name, explicit initial properties, and a result naming what was actually created.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CreateAssetFolderCommand` with a parent address, a name, a title, and initial properties whose removal list must be empty.
3. Implement `CreateAssetFolderResult` as the created folder's address and its title, so a caller comparing the reported address against the requested one catches a misplaced creation.
4. Declare exactly `parent_not_found`, `parent_access_denied`, `target_already_exists`, `property_rejected`, `repository_commit_failed`, `mutation_outcome_unknown`. A parent that exists and is not an asset folder is refused rather than accommodated, because creating asset structure under arbitrary content is how a repository stops being navigable.
5. Implement `CreateAssetFolderHandler` creating the folder with the platform's own folder type under the caller's session, in one commit.

**Tests:**

- A folder created under a parent that is content rather than asset structure is refused naming the parent's actual kind.
- A name that already exists is refused as already existing, and the existing node is asserted byte-identical afterwards.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`create_asset_folder` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `create_asset_folder` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CreateAssetFolderCommandTest && ./mvnw verify -pl aem -Dtest=CreateAssetFolderHandlerTest && ./mvnw verify -pl interop -Dtest=CreateAssetFolderScenario` proves a folder reported by its actual address, a non-asset parent refused naming its kind, and an untouched existing node on a name collision, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
