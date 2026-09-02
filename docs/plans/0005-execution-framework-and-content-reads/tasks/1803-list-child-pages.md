---
id: list-child-pages
title: "List Child Pages"
workstream: "0018"
kind: task
depends_on:
  - query-paths
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/ListChildPagesCommand.java
  - core/src/main/java/rs/slingshot/agent/command/content/ListChildPagesResult.java
  - core/src/main/java/rs/slingshot/agent/command/content/ListChildPagesHandler.java
  - policy/commands/list_child_pages.toml
  - "schemas/agent-protocol/command/list_child_pages-*.json"
  - schemas/agent-protocol-digests.toml
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/command/content/ListChildPagesCommandTest.java
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ListChildPagesScenario.java
  - policy/design-patterns.toml
  - interop/scenarios/list-child-pages.toml
status: done
merged_as: ""
---
# List Child Pages

Navigating a site one level at a time is the operation an operator does most and a query does worst. Listing children of a known page is a repository read rather than a search, and keeping it that way is what makes it fast on a tree that a query would have to walk.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListChildPagesCommand` with a parent address, a result window, and an optional continuation token.
3. Implement `ListChildPagesResult` as the child pages' addresses and titles in repository order, with a continuation token where more remain.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `root_not_found`, `root_access_denied`. A parent that exists and is not a page is a distinct refusal from one that does not exist, because the caller's next action differs.
5. Implement `ListChildPagesHandler` iterating children directly rather than issuing a query, under the caller's read-only resolver, so no index is involved and none is needed.

**Tests:**

- The handler is proved to issue no query at all, asserted against the declared-query inventory being empty for this command.
- Repository order is preserved across pages, proved by comparing the concatenated pages against a direct child iteration of the same parent.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_child_pages` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_child_pages` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListChildPagesCommandTest && ./mvnw verify -pl aem -Dtest=ListChildPagesHandlerTest && ./mvnw verify -pl interop -Dtest=ListChildPagesScenario` proves a query-free child iteration preserving repository order across pages, and a distinct refusal for a parent that is not a page, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
