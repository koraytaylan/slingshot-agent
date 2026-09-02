---
id: paged-query-and-continuation
title: "Paged Query and Continuation"
workstream: "0017"
kind: task
depends_on:
  - read-only-and-index-coverage
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/PagedQuery.java
  - core/src/main/java/rs/slingshot/agent/command/ResultWindow.java
  - core/src/main/java/rs/slingshot/agent/command/ContinuationRefusal.java
  - core/src/test/java/rs/slingshot/agent/command/PagedQueryTest.java
  - core/src/main/java/rs/slingshot/agent/continuation/ContinuationState.java
  - core/src/main/java/rs/slingshot/agent/continuation/ContinuationToken.java
  - "core/src/test/resources/fixtures/continuation-token/**"
  - schemas/agent-protocol-vectors.json
  - support/agent-contract.toml
  - support/agent-contract.sha256
  - support/command-contract.sha256
  - core/src/main/java/rs/slingshot/agent/contract/ContractLimit.java
  - core/src/main/java/rs/slingshot/agent/contract/AgentContract.java
  - core/pom.xml
  - "core/src/test/resources/fixtures/agent-contract/**"
  - core/src/test/java/rs/slingshot/agent/contract/AgentContractTest.java
  - policy/source-policy.toml
  - development/src/main/java/rs/slingshot/agent/development/SourcePolicy.java
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Paged Query and Continuation

A token is bound to its query, because a position in one result set is a perfectly plausible position in another. The six ways that check can fail are the six categories the client already declares, and each is reported as itself.

**Steps:**

1. Author fixtures for a first page, a continued page, a window of zero, a window above the row's maximum, and one fixture per continuation refusal category.
2. Implement `ResultWindow` bounded by the registry row: a caller asking for more than the row allows receives the row's maximum, because the row is the contract and the request is a preference; a caller asking for zero is refused, because a page of nothing is a question nobody meant to ask.
3. Implement `PagedQuery` issuing a token whenever more rows exist and none when they do not, so an absent token is a definite end rather than an unknown one.
4. Compute the query digest over the canonical bytes of every argument that changes which rows are returned or their order, and prove per-argument sensitivity one argument at a time.
5. Map the six continuation refusals — malformed, integrity invalid, wrong target, wrong query, expired, and a foreign generation — each to the category the client declares, with no shared fallback.

**Tests:**

- A first page returns rows and a token; the continued page returns the next rows and, at the end, no token.
- A window of zero is refused and a window above the maximum is answered with the maximum rather than refused.
- Each of the six continuation refusals is produced and reported as its own category, with no fallback path reachable.
- The query digest is proved sensitive to every argument that affects rows or order, one at a time, and insensitive to arguments that affect neither.
- A token from one query used on another is refused as wrong-query rather than as integrity-invalid, distinguishing two failures that would otherwise look alike.

- **Done when:** `./mvnw verify -pl core -Dtest=PagedQueryTest` proves paging to a definite end with tokens only where rows remain, a refused zero window and a clamped over-maximum one, six distinct continuation categories with no fallback, per-argument digest sensitivity in both directions, and a cross-query token refused as wrong-query.
