---
id: update-experience-fragment
title: "Update an Experience Fragment"
workstream: "0023"
kind: task
depends_on:
  - create-experience-fragment
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/fragment/UpdateExperienceFragmentCommand.java
  - core/src/main/java/rs/slingshot/agent/command/fragment/UpdateExperienceFragmentResult.java
  - core/src/main/resources/registry/update_experience_fragment.toml
  - "schemas/commands/update_experience_fragment/**"
  - core/src/test/java/rs/slingshot/agent/command/fragment/UpdateExperienceFragmentCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_experience_fragment/**"
  - aem/src/main/java/rs/slingshot/agent/aem/fragment/UpdateExperienceFragmentHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/fragment/UpdateExperienceFragmentHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdateExperienceFragmentScenario.java
  - interop/scenarios/update-experience-fragment.toml
status: done
merged_as: ""
---
# Update an Experience Fragment

The variation is the unit here rather than the fragment, and its failure set says so: every category names a variation. Addressing the fragment and expecting to change one is the mistake this shape prevents.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdateExperienceFragmentCommand` with the variation's address and a `PropertyChange`, addressing the variation directly rather than the fragment plus a name.
3. Implement `UpdateExperienceFragmentResult` as the variation's address, the properties actually set, and the properties actually removed.
4. Declare exactly `variation_not_found`, `variation_access_denied`, `variation_invalid`, `property_rejected`, `property_not_removable`, `repository_commit_failed`, `mutation_outcome_unknown`. Every declared category names a variation rather than a fragment, which is the shape telling a caller what the unit of change is.
5. Implement `UpdateExperienceFragmentHandler` applying both lists to the named variation in one commit under the caller's session.

**Tests:**

- Addressing the fragment rather than a variation is refused as an invalid variation rather than applied to a default one.
- A write to one variation leaves every other byte-identical.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_experience_fragment` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_experience_fragment` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdateExperienceFragmentCommandTest && ./mvnw verify -pl aem -Dtest=UpdateExperienceFragmentHandlerTest && ./mvnw verify -pl interop -Dtest=UpdateExperienceFragmentScenario` proves a fragment address refused rather than defaulted to a variation, a write confined to the named variation, and a protected removal refused before the commit, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
