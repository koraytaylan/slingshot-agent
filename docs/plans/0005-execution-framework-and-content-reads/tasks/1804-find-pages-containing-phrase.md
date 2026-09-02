---
id: find-pages-containing-phrase
title: "Find Pages Containing a Phrase"
workstream: "0018"
kind: task
depends_on:
  - list-child-pages
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesContainingPhraseCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesContainingPhraseHandler.java
  - core/src/main/java/rs/slingshot/agent/command/content/PageListingResult.java
  - policy/commands/find_pages_containing_phrase.toml
  - "schemas/agent-protocol/command/find_pages_containing_phrase-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - policy/query-index-coverage.toml
  - core/src/test/java/rs/slingshot/agent/command/content/FindPagesContainingPhraseCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/FindPagesContainingPhraseScenario.java
  - interop/scenarios/find-pages-containing-phrase.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Find Pages Containing a Phrase

Full-text search is where an unbounded query does the most damage, because the phrase comes from a caller and the caller has no idea what it will match. The budget is the whole safety story here, and it has to refuse rather than trim.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindPagesContainingPhraseCommand` with a root, the phrase as a canonical value the framework already defines, a result window, and an optional continuation token.
3. Implement `FindPagesContainingPhraseResult` as matching page addresses and titles, with a continuation token where more remain and no excerpt of the matched content.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `root_not_found`, `root_access_denied`. `discovery_budget_exceeded` is a refusal rather than a shortened answer, because a caller who received a trimmed page list would believe the rest do not match.
5. Implement `FindPagesContainingPhraseHandler` issuing one declared full-text query covered by the platform's own page index, counting examined nodes against the discovery budget and refusing at it.

**Tests:**

- The discovery budget refuses at exactly its limit rather than returning a shortened page, proved against a corpus one node past it.
- No result carries an excerpt or any content from the matched page beyond its address and title, asserted over a corpus containing distinctive text.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_pages_containing_phrase` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_pages_containing_phrase` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindPagesContainingPhraseCommandTest && ./mvnw verify -pl aem -Dtest=FindPagesContainingPhraseHandlerTest && ./mvnw verify -pl interop -Dtest=FindPagesContainingPhraseScenario` proves a budget that refuses rather than trims at exactly its limit, and results carrying no excerpt of matched content, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
