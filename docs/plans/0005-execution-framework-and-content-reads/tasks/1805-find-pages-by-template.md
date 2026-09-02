---
id: find-pages-by-template
title: "Find Pages by Template"
workstream: "0018"
kind: task
depends_on:
  - find-pages-containing-phrase
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesByTemplateCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesByTemplateResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/FindPagesByTemplateHandler.java
  - policy/commands/find_pages_by_template.toml
  - "schemas/agent-protocol/command/find_pages_by_template-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - policy/query-index-coverage.toml
  - core/src/test/java/rs/slingshot/agent/command/content/FindPagesByTemplateCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/FindPagesByTemplateScenario.java
  - interop/scenarios/find-pages-by-template.toml
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Find Pages by Template

The question behind most migrations: which pages would this change affect. It is answerable from an index the platform already maintains, which is exactly why it must not be answered any other way.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `FindPagesByTemplateCommand` with a root, the template address, a result window, and an optional continuation token.
3. Implement `FindPagesByTemplateResult` as matching page addresses and their last-modified instants, with a continuation token where more remain.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `root_not_found`, `root_access_denied`. A root that does not exist is refused rather than answered with an empty page, because an empty answer to a mistyped root reads as a migration with nothing to do.
5. Implement `FindPagesByTemplateHandler` issuing one declared query on the template property, covered by the platform's own page index, under the caller's read-only resolver.

**Tests:**

- A mistyped root is refused rather than returning an empty page, and a correct root with no matches returns an empty page rather than a refusal.
- The query is asserted covered by a platform-provided index on every deployment row, with no index shipped by this build.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`find_pages_by_template` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `find_pages_by_template` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=FindPagesByTemplateCommandTest && ./mvnw verify -pl aem -Dtest=FindPagesByTemplateHandlerTest && ./mvnw verify -pl interop -Dtest=FindPagesByTemplateScenario` proves a refused mistyped root distinguished from a genuinely empty result, and index coverage on every deployment row with nothing shipped, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
