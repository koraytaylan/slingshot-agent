---
id: update-content-fragment
title: "Update a Content Fragment"
workstream: "0023"
kind: task
depends_on:
  - create-content-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/UpdateContentFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/UpdateContentFragmentResult.java
  - core/src/main/resources/registry/update_content_fragment.toml
  - "schemas/commands/update_content_fragment/**"
  - core/src/test/java/rs/slingshot/agent/command/fragment/UpdateContentFragmentCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_content_fragment/**"
  - aem/src/main/java/rs/slingshot/agent/aem/fragment/UpdateContentFragmentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/fragment/UpdateContentFragmentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdateContentFragmentScenario.java
  - interop/scenarios/update-content-fragment.toml
status: done
merged_as: ""
---
# Update a Content Fragment

Updating a fragment without naming the variation updates whichever one somebody made first. The variation is required here for the same reason it is required on the read: there is no correct default and guessing produces edits nobody asked for.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdateContentFragmentCommand` with the fragment address, a required variation name, and the elements to set, using the shared element vocabulary.
3. Implement `UpdateContentFragmentResult` as the address, the variation, and the elements actually written.
4. Declare exactly `fragment_not_found`, `fragment_access_denied`, `fragment_invalid`, `variation_not_found`, `element_unknown`, `element_value_rejected`, `repository_commit_failed`, `mutation_outcome_unknown`. A variation that does not exist is distinct from a fragment that does not exist, because a caller who mistyped a variation has a different next step from one who mistyped an address.
5. Implement `UpdateContentFragmentHandler` writing only the named variation, in one commit under the caller's session, and refusing an element the model does not declare.

**Tests:**

- A write to one variation is proved to leave every other variation byte-identical.
- A missing variation and a missing fragment are two distinct refusals, and neither writes anything.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_content_fragment` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_content_fragment` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdateContentFragmentCommandTest && ./mvnw verify -pl aem -Dtest=UpdateContentFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=UpdateContentFragmentScenario` proves a write confined to the named variation with every other left byte-identical, a required variation with no default, and distinct missing-variation and missing-fragment refusals that write nothing, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
