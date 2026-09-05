---
id: bounded-component-deletion
title: "Restrict Component Deletion to Valid Bounded Components"
workstream: "0044"
kind: task
depends_on:
  - bounded-repository-traversal
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/component/ComponentPathHandler.java
  - core/src/main/java/rs/slingshot/agent/command/component/ComponentParent.java
  - core/src/test/java/rs/slingshot/agent/command/component
  - policy/commands
  - schemas/agent-protocol/command
  - core/src/test/resources/fixtures/agent-contract
status: pending
merged_as: ""
---
# Restrict Component Deletion to Valid Bounded Components

Finding(s): R12 in [FINDINGS.md](../FINDINGS.md).

Failure categories must remain within the authoritative client contract. If the required refusal
cannot be expressed by the current row, coordinate the registry/schema/digest correction and its
checker fixtures rather than emitting an undeclared category.

**Steps:**

1. Reproduce successful deletion of an ordinary folder and children with discovery budget one.
2. Validate component identity and parent/context before staging, and apply deletion/traversal bounds to the entire component subtree.
3. Test ordinary folders, pages, site roots, invalid parents and over-budget component trees alongside a valid deletion.

- **Done when:** Only a valid component within the declared deletion budget can be deleted; wrong-kind and oversized targets return the declared refusal with byte-identical repository content.
