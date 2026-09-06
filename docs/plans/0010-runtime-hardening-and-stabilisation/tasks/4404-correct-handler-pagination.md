---
id: correct-handler-pagination
title: "Apply Verified Paging on Every Handler Run Path"
workstream: "0044"
kind: task
depends_on:
  - bounded-repository-traversal
  - fenced-continuation-authority
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/ResultWindow.java
  - core/src/main/java/rs/slingshot/agent/command/PagedQuery.java
  - core/src/main/java/rs/slingshot/agent/command/content
  - core/src/main/java/rs/slingshot/agent/command/configuration
  - core/src/main/java/rs/slingshot/agent/command/framework
  - core/src/main/java/rs/slingshot/agent/command/job
  - core/src/main/java/rs/slingshot/agent/command/principal
  - core/src/main/java/rs/slingshot/agent/command/replication
  - core/src/main/java/rs/slingshot/agent/command/workflow
  - core/src/test/java/rs/slingshot/agent/command
status: complete
merged_as: "2e0f4d5"
---
# Apply Verified Paging on Every Handler Run Path

Finding(s): R13 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Enumerate every paged registry row and reproduce the ignored window, missing next token and accepted nonsense token through each affected run path.
2. Connect query-bound token validation/issuance and requested offset/limit to actual dispatch, rejecting invalid initial bounds and guaranteeing a token exactly when more results exist.
3. Concatenate multiple pages and compare to an independently ordered source; test malformed, wrong-query, stale and expired tokens and final-page absence.

- **Done when:** Every paged command respects its requested window, validates continuation authority, returns each expected row once across pages, and omits a next token only at the true end.
