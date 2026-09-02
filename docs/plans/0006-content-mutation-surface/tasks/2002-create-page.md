---
id: create-page
title: "Create a Page"
workstream: "0020"
kind: task
depends_on:
  - mutation-vocabulary
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/page/CreatePageCommand.java
  - core/src/main/java/rs/slingshot/agent/command/page/CreatePageResult.java
  - core/src/main/resources/registry/create_page.toml
  - "schemas/commands/create_page/**"
  - core/src/test/java/rs/slingshot/agent/command/page/CreatePageCommandTest.java
  - "core/src/test/resources/fixtures/commands/create_page/**"
  - aem/src/main/java/rs/slingshot/agent/aem/page/CreatePageHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/page/CreatePageHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CreatePageScenario.java
  - interop/scenarios/create-page.toml
status: done
merged_as: ""
---
# Create a Page

The first write, and the one that decides what a created thing looks like. A page created without its template is a node that renders as nothing, so the template is required and an unresolvable one is refused rather than left to produce a page nobody can open.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CreatePageCommand` with a parent address, a name, a title, a required template address, and the initial properties as a `PropertyChange` whose removal list must be empty.
3. Implement `CreatePageResult` as the created page's address and the template it was created from, so a caller comparing the reported address against the requested one catches a whole class of defect.
4. Declare exactly `target_already_exists`, `parent_not_found`, `parent_access_denied`, `template_not_found`, `template_invalid`, `property_rejected`, `repository_commit_failed`, `mutation_outcome_unknown`. A template that does not resolve and one that resolves and is not a template are two distinct refusals, because the first is usually a typo and the second is usually a misunderstanding.
5. Implement `CreatePageHandler` creating the page through the platform's own page manager under the caller's session, inside the single-commit wrapper.

**Tests:**

- A page created at an existing address is refused as already existing and the existing page is asserted byte-identical afterwards.
- A removal list on creation is refused at construction, since there is nothing to remove from a page that does not exist.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`create_page` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `create_page` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CreatePageCommandTest && ./mvnw verify -pl aem -Dtest=CreatePageHandlerTest && ./mvnw verify -pl interop -Dtest=CreatePageScenario` proves a created page reported by its actual address and template, distinct unresolvable-template and not-a-template refusals, an untouched existing page on a name collision, and a removal list refused at construction, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
