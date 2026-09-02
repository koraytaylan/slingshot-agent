---
id: update-page
title: "Update a Page"
workstream: "0020"
kind: task
depends_on:
  - create-page
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/page/UpdatePageCommand.java
  - core/src/main/java/rs/slingshot/agent/command/page/UpdatePageResult.java
  - core/src/main/resources/registry/update_page.toml
  - "schemas/commands/update_page/**"
  - core/src/test/java/rs/slingshot/agent/command/page/UpdatePageCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_page/**"
  - aem/src/main/java/rs/slingshot/agent/aem/page/UpdatePageHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/page/UpdatePageHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdatePageScenario.java
  - interop/scenarios/update-page.toml
status: done
merged_as: ""
---
# Update a Page

The command where the absent property question is decided. An update that treated absence as removal would make a caller who sent a partial view destroy the rest, and one that treated an empty value as removal would make an intentionally empty title impossible.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdatePageCommand` with a page address and a `PropertyChange` carrying the properties to set and the properties to remove by name.
3. Implement `UpdatePageResult` as the page's address, the properties actually set, and the properties actually removed, so a caller sees what took rather than what was asked.
4. Declare exactly `page_not_found`, `page_access_denied`, `page_invalid`, `property_rejected`, `property_not_removable`, `repository_commit_failed`, `mutation_outcome_unknown`. `property_not_removable` exists because a caller told a removal succeeded will build on it, and a protected property that silently stayed is the defect that surfaces three commands later.
5. Implement `UpdatePageHandler` applying both lists in one session and committing once, refusing before the commit if any named property cannot be removed.

**Tests:**

- A property named in neither list is asserted unchanged, byte for byte, across an update that changes its siblings.
- A protected property named for removal refuses before the commit, and every other property in the same request is asserted unchanged.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_page` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_page` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdatePageCommandTest && ./mvnw verify -pl aem -Dtest=UpdatePageHandlerTest && ./mvnw verify -pl interop -Dtest=UpdatePageScenario` proves an absent property left byte-identical while its siblings change, and a protected removal refused before the commit with the rest of the request unapplied, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
