---
id: query-paths
title: "Query Paths"
workstream: "0018"
kind: task
depends_on:
  - load-content-as-json
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/QueryPathsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/QueryPathsResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/QueryPathsHandler.java
  - core/src/main/java/rs/slingshot/agent/command/ResultWindow.java
  - policy/commands/query_paths.toml
  - "schemas/agent-protocol/command/query_paths-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - policy/query-index-coverage.toml
  - core/src/test/java/rs/slingshot/agent/command/content/QueryPathsCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - development/src/main/java/rs/slingshot/agent/development/SchemaCorrespondence.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/QueryPathsScenario.java
  - interop/scenarios/query-paths.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Query Paths

The simplest paged command, and therefore the one the paging machinery is proved on. It returns addresses and nothing else, which makes it the one command where a leak of anything beyond an address would be unmistakable.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `QueryPathsCommand` with a root, a node-type restriction, a result window, and an optional continuation token.
3. Implement `QueryPathsResult` as an ordered list of addresses and a continuation token where more rows exist, with no property value of any kind.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `root_not_found`, `root_access_denied`. The six continuation categories come from the framework unchanged; a seventh spelling of them is refused by the conformance gate.
5. Implement `QueryPathsHandler` issuing one declared query covered by an index the deployment already provides, under the caller's read-only resolver, and ordering results so two pages never overlap or skip.

**Tests:**

- Paging across a corpus larger than several windows returns every address exactly once and in a stable order, proved by comparing the concatenated pages against a single unbounded read of the same corpus.
- The result carries no property value, asserted over a corpus whose nodes carry distinctive property values that must not appear.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`query_paths` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `query_paths` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=QueryPathsCommandTest && ./mvnw verify -pl aem -Dtest=QueryPathsHandlerTest && ./mvnw verify -pl interop -Dtest=QueryPathsScenario` proves gapless non-overlapping paging in a stable order against an unbounded read, an address-only result with no property value disclosed, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
