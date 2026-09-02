---
id: update-user-profile
title: "Update a User Profile"
workstream: "0028"
kind: task
depends_on:
  - create-authorizable
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/authorizable/UpdateUserProfileCommand.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/UpdateUserProfileResult.java
  - core/src/main/resources/registry/update_user_profile.toml
  - "schemas/commands/update_user_profile/**"
  - core/src/test/java/rs/slingshot/agent/command/authorizable/UpdateUserProfileCommandTest.java
  - "core/src/test/resources/fixtures/commands/update_user_profile/**"
  - aem/src/main/java/rs/slingshot/agent/aem/authorizable/UpdateUserProfileHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/authorizable/UpdateUserProfileHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/UpdateUserProfileScenario.java
  - interop/scenarios/update-user-profile.toml
status: done
merged_as: ""
---
# Update a User Profile

A profile is ordinary properties on an account, which makes it the same two-list update everything else gets. What is different is that some of those properties are the platform's own and removing one is how an account stops working.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `UpdateUserProfileCommand` with the authorizable identifier and a `PropertyChange`, using Plan 0006's shared type.
3. Implement `UpdateUserProfileResult` as the identifier, the properties actually set, and the properties actually removed.
4. Declare exactly `authorizable_not_found`, `authorizable_kind_mismatch`, `authorizable_access_denied`, `property_rejected`, `property_not_removable`, `repository_commit_failed`, `mutation_outcome_unknown`. `authorizable_kind_mismatch` exists because addressing a group where a user belongs is a mistake a caller makes constantly, and applying a profile update to a group would half-work.
5. Implement `UpdateUserProfileHandler` writing into the account's own profile node and nowhere else, in one commit under the caller's session.

**Tests:**

- A write is proved confined to the profile node, with the rest of the account including its membership asserted byte-identical.
- A platform-maintained profile property named for removal is refused before the commit, and the account is asserted unchanged.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`update_user_profile` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `update_user_profile` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=UpdateUserProfileCommandTest && ./mvnw verify -pl aem -Dtest=UpdateUserProfileHandlerTest && ./mvnw verify -pl interop -Dtest=UpdateUserProfileScenario` proves a write confined to the profile node with membership untouched, a platform-maintained removal refused before the commit, and a group address refused as a kind mismatch, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
