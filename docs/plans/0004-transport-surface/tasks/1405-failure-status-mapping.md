---
id: failure-status-mapping
title: "Failure Status Mapping"
workstream: "0014"
kind: task
depends_on:
  - subscription-high-water-route
gated: false
touches:
  - policy/failure-status-mapping.toml
  - core/src/main/java/rs/slingshot/agent/http/StatusMapping.java
  - core/src/main/java/rs/slingshot/agent/http/RetryHint.java
  - core/src/test/java/rs/slingshot/agent/http/StatusMappingTest.java
  - development/src/main/java/rs/slingshot/agent/development/StatusMappingCoverage.java
  - development/src/test/java/rs/slingshot/agent/development/StatusMappingCoverageTest.java
status: done
merged_as: ""
---
# Failure Status Mapping

A retry hint on a refusal that will never succeed is an instruction to waste an author's request budget. So the mapping is data, one row per category, and a category with no row is a build failure rather than a default.

**Steps:**

1. Author fixtures for a category with no row, a row for no category, a retry hint above the contract's cap, and a hint on a refusal marked unretryable.
2. Write `policy/failure-status-mapping.toml` with one row per category naming the status, whether the refusal is retryable, and whether a hint accompanies it.
3. Implement `StatusMapping` reading that file, with no default branch: a category with no row cannot be rendered.
4. Implement `RetryHint` capped at the contract's own cap, so this side never asks a client to wait longer than the client's policy allows, and refuse a hint on an unretryable row.
5. Implement the coverage check comparing the mapping's category set against the categories the protocol declares, in both directions.

**Tests:**

- Every declared category has exactly one row and every row names a declared category.
- A category with no row cannot be rendered, asserted by attempting it.
- A hint above the cap is refused rather than clamped, and a hint on an unretryable row is refused naming the row.
- Every unretryable category is asserted to carry no hint, across the whole mapping.
- The rendered response for each category carries the category, the status, and nothing the error document does not declare.

- **Done when:** `./mvnw verify -pl core -Dtest=StatusMappingTest && ./mvnw verify -pl development -Dtest=StatusMappingCoverageTest` proves two-way category-to-row correspondence with no default branch, a refused rather than clamped over-cap hint, no hint on any unretryable row, and responses carrying nothing beyond the declared error document.
