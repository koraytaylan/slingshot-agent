---
id: list-asset-renditions
title: "List Asset Renditions"
workstream: "0019"
kind: task
depends_on:
  - find-assets-referenced-by-page
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/ListAssetRenditionsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/ListAssetRenditionsResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/ListAssetRenditionsHandler.java
  - policy/commands/list_asset_renditions.toml
  - "schemas/agent-protocol/command/list_asset_renditions-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/ListAssetRenditionsCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ListAssetRenditionsScenario.java
  - interop/scenarios/list-asset-renditions.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# List Asset Renditions

Renditions are where an asset's storage actually goes, and an operator asking why a repository is large is asking this question. It reports sizes and never bytes, because an answer that carried the renditions would be the thing it is measuring.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListAssetRenditionsCommand` with an asset address, a result window, and an optional continuation token.
3. Implement `ListAssetRenditionsResult` as each rendition's name, media type, byte size, and dimensions where the platform records them, and never any rendition content.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `asset_not_found`, `asset_access_denied`, `asset_invalid`. An address that exists and is not an asset is a distinct refusal from an asset whose rendition structure is malformed, because the first is a caller error and the second is a repository somebody has to look at.
5. Implement `ListAssetRenditionsHandler` iterating the asset's renditions directly rather than querying, under the caller's read-only resolver.

**Tests:**

- Every rendition is listed with its recorded size, and the original is included and marked as such rather than omitted.
- No response carries rendition content or a repository path to one, asserted over an asset with several renditions.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_asset_renditions` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_asset_renditions` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListAssetRenditionsCommandTest && ./mvnw verify -pl aem -Dtest=ListAssetRenditionsHandlerTest && ./mvnw verify -pl interop -Dtest=ListAssetRenditionsScenario` proves every rendition listed with its recorded size including a marked original, and no rendition content or repository path disclosed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
