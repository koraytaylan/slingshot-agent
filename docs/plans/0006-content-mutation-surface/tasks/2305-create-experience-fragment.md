---
id: create-experience-fragment
title: "Create an Experience Fragment"
workstream: "0023"
kind: task
depends_on:
  - delete-content-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/CreateExperienceFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/CreateExperienceFragmentResult.java
  - core/src/main/resources/registry/create_experience_fragment.toml
  - "schemas/commands/create_experience_fragment/**"
  - core/src/test/java/rs/slingshot/agent/command/fragment/CreateExperienceFragmentCommandTest.java
  - "core/src/test/resources/fixtures/commands/create_experience_fragment/**"
  - aem/src/main/java/rs/slingshot/agent/aem/fragment/CreateExperienceFragmentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/fragment/CreateExperienceFragmentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CreateExperienceFragmentScenario.java
  - interop/scenarios/create-experience-fragment.toml
status: done
merged_as: ""
---
# Create an Experience Fragment

An experience fragment is a page-shaped thing built from a template rather than a model, which means it fails the way a page does and reads the way a fragment does. Keeping the two vocabularies distinct is what stops a caller assuming a model where a template belongs.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CreateExperienceFragmentCommand` with a parent address, a name, a title, and a required template address, with the variation created explicitly rather than implied.
3. Implement `CreateExperienceFragmentResult` as the created fragment's address, its template, and the variation created.
4. Declare exactly `parent_not_found`, `parent_access_denied`, `target_already_exists`, `template_not_found`, `template_invalid`, `repository_commit_failed`, `mutation_outcome_unknown`. A template that does not resolve and one that resolves and is not an experience-fragment template are two distinct refusals, because the second is the mistake a caller makes when reusing a page template.
5. Implement `CreateExperienceFragmentHandler` resolving the template first and refusing rather than proceeding, then creating the fragment and its variation in one commit under the caller's session.

**Tests:**

- A page template supplied where an experience-fragment template belongs is refused as invalid rather than accepted, and nothing is written.
- The created variation is reported explicitly, and a fixture creating a fragment with no variation is refused.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`create_experience_fragment` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `create_experience_fragment` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CreateExperienceFragmentCommandTest && ./mvnw verify -pl aem -Dtest=CreateExperienceFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=CreateExperienceFragmentScenario` proves a page template refused as invalid with nothing written, distinct unresolvable-template and wrong-kind refusals, and an explicitly reported created variation, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
