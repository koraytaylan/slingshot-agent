---
id: find-assets-by-metadata
title: "Find Assets by Metadata"
workstream: "0019"
kind: task
depends_on:
  - find-pages-using-components
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/AssetMetadataPredicate.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindAssetsByMetadataCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindAssetsByMetadataResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindAssetsByMetadataHandler.java
  - policy/commands/find_assets_by_metadata.toml
  - "schemas/agent-protocol/command/find_assets_by_metadata-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - policy/query-index-coverage.toml
  - core/src/test/java/rs/slingshot/agent/command/content/FindAssetsByMetadataCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/FindAssetsByMetadataScenario.java
  - interop/scenarios/find-assets-by-metadata.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Find Assets by Metadata

Asset metadata is the one place in an Adobe Experience Manager repository where a customer has put arbitrary properties, so this is the command most likely to be asked a question no index answers. Refusing that question is better than walking a million assets to answer it.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindAssetsByMetadataCommand` with a root, a closed set of metadata predicates the platform's asset index actually supports, a result window, and an optional continuation token.
3. Implement `FindAssetsByMetadataResult` as matching asset addresses with their declared metadata values only, never the whole metadata node.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `root_not_found`, `root_access_denied`. A predicate the asset index does not cover is refused at construction rather than at run time, so a caller learns their question is unanswerable before an author spends anything on it.
5. Implement `FindAssetsByMetadataHandler` issuing one declared query on the platform's own asset index, under the caller's read-only resolver, counting examined nodes against the discovery budget.

**Tests:**

- Every supported predicate is proved covered by the platform's asset index on every deployment row; a predicate outside the closed set is refused at construction.
- The result carries only the metadata the command declared, asserted over assets whose metadata nodes carry additional distinctive properties that must not appear.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_assets_by_metadata` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_assets_by_metadata` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindAssetsByMetadataCommandTest && ./mvnw verify -pl aem -Dtest=FindAssetsByMetadataHandlerTest && ./mvnw verify -pl interop -Dtest=FindAssetsByMetadataScenario` proves a closed predicate set proved index-covered on every deployment row with anything outside it refused at construction, and results carrying only declared metadata, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
