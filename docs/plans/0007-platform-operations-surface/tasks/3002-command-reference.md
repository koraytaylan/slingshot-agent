---
id: command-reference
title: "Command Reference"
workstream: "0030"
kind: task
depends_on:
  - sixty-four-row-registry
gated: false
touches:
  - docs/COMMANDS.md
  - development/src/main/java/rs/slingshot/agent/development/CommandReference.java
  - development/src/test/java/rs/slingshot/agent/development/CommandReferenceTest.java
  - policy/documentation-rules.toml
  - docs/DOCUMENTATION_REVIEW.md
status: done
merged_as: ""
---
# Command Reference

A reference maintained beside a registry is a reference that is wrong within a month. Generating it from the registry is what makes it something a reader can rely on, and it is the same rule the client applies to its own.

**Steps:**

1. Author fixtures for a reference missing a row, carrying a row the registry does not have, and whose rendered values differ from the registry's.
2. Implement `CommandReference` rendering the whole table from the registry directory: wire name, summary, access class, operation-key requirement, result bound, and declared failure categories.
3. Render into `docs/COMMANDS.md` between explicit generated markers, so the surrounding prose is written by hand and the table is not.
4. Check the rendered table against the registry on every build, so a command that exists appears in it or the build does not pass.
5. Record what the check cannot decide — whether the summaries are accurate and whether the surrounding prose is worth reading — as review checklist entries rather than as checks that pretend to.

**Tests:**

- The rendered table equals the registry row for row and field for field; a fixture reference missing a row, carrying an extra one, or differing in a value fails naming it.
- The generated region is delimited and the surrounding prose is asserted untouched across a regeneration.
- Regenerating twice produces byte-identical output.
- Every declared failure category of every command appears in the reference, in the registry's own order.
- The review checklist entries are asserted to be exactly what the check does not decide, and a fixture check restating one is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=CommandReferenceTest && scripts/quality` proves a table equal to the registry row for row and field for field with three distinct divergence findings, byte-identical regeneration leaving hand-written prose untouched, every declared category rendered in registry order, and a review checklist the check does not pretend to decide.
