---
id: find-assets-referenced-by-page
title: "Find Assets Referenced by a Page"
workstream: "0019"
kind: task
depends_on:
  - find-assets-by-metadata
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/ReferenceKind.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindAssetsReferencedByPageCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindAssetsReferencedByPageResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindAssetsReferencedByPageHandler.java
  - policy/commands/find_assets_referenced_by_page.toml
  - "schemas/agent-protocol/command/find_assets_referenced_by_page-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/FindAssetsReferencedByPageCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/FindAssetsReferencedByPageScenario.java
  - interop/scenarios/find-assets-referenced-by-page.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Find Assets Referenced by a Page

A reference to an asset can be a property value, a fragment of markup, or a path inside a structured value, and a command that only finds one kind reports confidently that a page uses nothing. Which kinds are searched has to be stated rather than implied.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindAssetsReferencedByPageCommand` with a page address, the reference kinds to search as an explicit closed set, a result window, and an optional continuation token.
3. Implement `FindAssetsReferencedByPageResult` as referenced asset addresses each with the kind of reference and the property it was found in, deduplicated per asset.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `page_not_found`, `page_access_denied`, `page_invalid`. A page that exists and is not a page is a distinct refusal from one that does not exist and from one the caller cannot read, because all three are ordinary and lead somewhere different.
5. Implement `FindAssetsReferencedByPageHandler` walking the page's own subtree rather than querying, examining exactly the declared reference kinds, and counting nodes against the discovery budget.

**Tests:**

- Each declared reference kind is found on a fixture page carrying one of each, and a reference of a kind not requested is not reported.
- An asset referenced several times on one page appears once, with every distinct kind and property listed.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_assets_referenced_by_page` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_assets_referenced_by_page` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindAssetsReferencedByPageCommandTest && ./mvnw verify -pl aem -Dtest=FindAssetsReferencedByPageHandlerTest && ./mvnw verify -pl interop -Dtest=FindAssetsReferencedByPageScenario` proves every declared reference kind found and every undeclared kind ignored, with per-asset deduplication listing each distinct kind and property, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
