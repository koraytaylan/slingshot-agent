---
id: find-pages-using-components
title: "Find Pages Using Components"
workstream: "0018"
kind: task
depends_on:
  - find-pages-by-template
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesUsingComponentsCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesUsingComponentsResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesUsingComponentsHandler.java
  - policy/commands/find_pages_using_components.toml
  - "schemas/agent-protocol/command/find_pages_using_components-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - policy/query-index-coverage.toml
  - core/src/test/java/rs/slingshot/agent/command/content/FindPagesUsingComponentsCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/FindPagesUsingComponentsScenario.java
  - interop/scenarios/find-pages-using-components.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Find Pages Using Components

The other migration question, and the harder one: a component appears anywhere inside a page rather than as a property of it, so the match is on a descendant and the answer has to be the page rather than the node that matched.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindPagesUsingComponentsCommand` with a root, one or more component resource types, a result window, and an optional continuation token.
3. Implement `FindPagesUsingComponentsResult` as matching page addresses each with the component types found on it, deduplicated, so a page with forty matching nodes appears once.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `root_not_found`, `root_access_denied`. An empty component list is refused at construction rather than matching everything, because a query with no restriction is the traversal this build does not do.
5. Implement `FindPagesUsingComponentsHandler` issuing one declared query on the resource type, folding descendant matches up to their containing page, and counting every examined node against the discovery budget.

**Tests:**

- A page containing many matching nodes appears exactly once, with each distinct component type listed once.
- An empty component list is refused at construction, and a component type that matches nothing yields an empty page rather than a refusal.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_pages_using_components` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_pages_using_components` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindPagesUsingComponentsCommandTest && ./mvnw verify -pl aem -Dtest=FindPagesUsingComponentsHandlerTest && ./mvnw verify -pl interop -Dtest=FindPagesUsingComponentsScenario` proves per-page deduplication with distinct component types listed once, and an empty component list refused at construction, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
