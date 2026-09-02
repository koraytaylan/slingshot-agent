---
id: delete-authorizable
title: "Delete an Authorizable"
workstream: "0028"
kind: task
depends_on:
  - set-user-disabled
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/authorizable/DeleteAuthorizableCommand.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/DeleteAuthorizableResult.java
  - core/src/main/resources/registry/delete_authorizable.toml
  - "schemas/commands/delete_authorizable/**"
  - core/src/test/java/rs/slingshot/agent/command/authorizable/DeleteAuthorizableCommandTest.java
  - "core/src/test/resources/fixtures/commands/delete_authorizable/**"
  - aem/src/main/java/rs/slingshot/agent/aem/authorizable/DeleteAuthorizableHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/authorizable/DeleteAuthorizableHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/DeleteAuthorizableScenario.java
  - interop/scenarios/delete-authorizable.toml
status: done
merged_as: ""
---
# Delete an Authorizable

Deleting an account removes it from every group it was in and every access-control entry naming it, which is a blast radius the caller cannot see from the request they wrote. Refusing a group that still has members rather than cascading is the one guard that keeps that radius visible.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `DeleteAuthorizableCommand` with the identifier and a required expected kind, so deleting the wrong sort of thing takes two mistakes rather than one.
3. Implement `DeleteAuthorizableResult` as the identifier removed, its kind, and the number of group memberships that ended with it.
4. Declare exactly `authorizable_not_found`, `authorizable_kind_mismatch`, `authorizable_access_denied`, `group_has_members`, `repository_commit_failed`, `mutation_outcome_unknown`. `group_has_members` refuses rather than cascades, because cascading a membership deletion is a change whose consequences the caller did not ask about.
5. Implement `DeleteAuthorizableHandler` checking the expected kind, refusing a group with members, and removing through the platform's own user management in one commit under the caller's session.

**Tests:**

- A group with members is refused with the group and every member asserted unchanged, and the refusal reports how many members without naming them.
- An expected kind that does not match refuses with nothing removed.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`delete_authorizable` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `delete_authorizable` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=DeleteAuthorizableCommandTest && ./mvnw verify -pl aem -Dtest=DeleteAuthorizableHandlerTest && ./mvnw verify -pl interop -Dtest=DeleteAuthorizableScenario` proves a group with members refused rather than cascaded with a member count and no names, a required expected kind refusing a mismatch with nothing removed, and the ended memberships counted, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
