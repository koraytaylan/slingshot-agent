---
id: complete-reference-guards
title: "Refuse Mutations After Incomplete Reference Discovery"
workstream: "0044"
kind: task
depends_on:
  - bounded-repository-traversal
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/mutation/RepositoryReach.java
  - core/src/main/java/rs/slingshot/agent/command/page/DeletePageHandler.java
  - core/src/main/java/rs/slingshot/agent/command/page/MovePageHandler.java
  - core/src/main/java/rs/slingshot/agent/command/asset/AssetMutationHandler.java
  - core/src/main/java/rs/slingshot/agent/command/fragment
  - core/src/test/java/rs/slingshot/agent/command
  - policy/commands
  - schemas/agent-protocol/command
  - core/src/test/resources/fixtures/agent-contract
status: complete
merged_as: 1805837
---
# Refuse Mutations After Incomplete Reference Discovery

Finding(s): R11 in [FINDINGS.md](../FINDINGS.md).

Failure categories must remain within the authoritative client contract. If the required refusal
cannot be expressed by the current row, coordinate the registry/schema/digest correction and its
checker fixtures rather than emitting an undeclared category.

**Steps:**

1. Turn the delete-with-refuse_when_referenced counterexample into a regression and enumerate all reference-guard consumers.
2. Require complete reference discovery before authorizing delete or reference adjustment; use approved indexed lookup where appropriate and state the caller-visibility guarantee.
3. Test references after the boundary, multivalue links and insufficient visibility; verify failure leaves content and links unchanged.

- **Done when:** Every destructive/reference-adjusting command refuses incomplete discovery before changing content, and no permitted deletion/move leaves a link unhandled within its declared reference guarantee.
