---
id: create-content-fragment
title: "Create a Content Fragment"
workstream: "0023"
kind: task
depends_on:
  - fragment-element-vocabulary
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/CreateContentFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/CreateContentFragmentResult.java
  - core/src/main/resources/registry/create_content_fragment.toml
  - "schemas/commands/create_content_fragment/**"
  - core/src/test/java/rs/slingshot/agent/command/fragment/CreateContentFragmentCommandTest.java
  - "core/src/test/resources/fixtures/commands/create_content_fragment/**"
  - aem/src/main/java/rs/slingshot/agent/aem/fragment/CreateContentFragmentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/fragment/CreateContentFragmentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CreateContentFragmentScenario.java
  - interop/scenarios/create-content-fragment.toml
status: done
merged_as: ""
---
# Create a Content Fragment

A fragment is only meaningful against its model, and creating one whose model cannot be resolved produces a node that every tool reads differently. Refusing before writing is the only outcome that leaves the repository describable.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CreateContentFragmentCommand` with a parent address, a name, a title, a required model address, and the initial elements as typed `FragmentElement` values.
3. Implement `CreateContentFragmentResult` as the created fragment's address, the model it was created against, and the elements actually written.
4. Declare exactly `parent_not_found`, `parent_access_denied`, `target_already_exists`, `model_not_found`, `model_invalid`, `element_unknown`, `element_value_rejected`, `repository_commit_failed`, `mutation_outcome_unknown`. An element the model does not declare and a value the element's own constraints reject are two distinct refusals, because one is a caller using the wrong model and the other is a caller sending the wrong value.
5. Implement `CreateContentFragmentHandler` resolving the model first and refusing rather than proceeding untyped, then writing the fragment and its master variation in one commit under the caller's session.

**Tests:**

- A model that does not resolve is refused before anything is written, and the parent is asserted byte-identical afterwards.
- An undeclared element and a constraint-rejected value are refused distinctly, each naming the element, with nothing written in either case.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`create_content_fragment` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `create_content_fragment` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CreateContentFragmentCommandTest && ./mvnw verify -pl aem -Dtest=CreateContentFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=CreateContentFragmentScenario` proves an unresolvable model refused before any write with the parent untouched, and distinct undeclared-element and rejected-value refusals each naming the element with nothing written, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
