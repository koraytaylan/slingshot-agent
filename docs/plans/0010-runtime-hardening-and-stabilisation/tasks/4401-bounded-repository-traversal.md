---
id: bounded-repository-traversal
title: "Bound Repository Reads and Retained Traversal Work"
workstream: "0044"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/mutation/RepositoryReach.java
  - core/src/main/java/rs/slingshot/agent/command/content/QueryPathsHandler.java
  - core/src/main/java/rs/slingshot/agent/command/content/ListChildPagesHandler.java
  - core/src/main/java/rs/slingshot/agent/command/content/DownloadContentPackageHandler.java
  - core/src/main/java/rs/slingshot/agent/command/Budget.java
  - core/src/test/java/rs/slingshot/agent/command
status: complete
merged_as: 829051b
---
# Bound Repository Reads and Retained Traversal Work

Finding(s): R14 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce bound=1 consuming a 10,000-child iterator and cases with many nonmatching children.
2. Enforce read, retained-work and duration budgets before advancing iterators or queuing children, returning explicit exhausted outcomes without claiming completeness.
3. Apply the traversal contract to all shared callers and verify ordering on wide and deep trees with measured iterator calls.

- **Done when:** Instrumented traversals across affected handlers respect declared repository-read, retained-work and duration bounds on wide/deep/nonmatching trees without silently presenting partial work as complete.
