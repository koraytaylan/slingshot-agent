---
id: group-membership
title: "Group Membership"
workstream: "0028"
kind: task
depends_on:
  - delete-authorizable
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/authorizable/GroupMembershipCommand.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/GroupMembershipResult.java
  - core/src/main/resources/registry/add_group_member.toml
  - "schemas/commands/add_group_member/**"
  - core/src/main/resources/registry/remove_group_member.toml
  - "schemas/commands/remove_group_member/**"
  - core/src/test/java/rs/slingshot/agent/command/authorizable/GroupMembershipCommandTest.java
  - "core/src/test/resources/fixtures/commands/add_group_member/**"
  - aem/src/main/java/rs/slingshot/agent/aem/authorizable/GroupMembershipHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/authorizable/GroupMembershipHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/GroupMembershipScenario.java
  - interop/scenarios/add-group-member.toml
status: done
merged_as: ""
---
# Group Membership

One task because adding and removing a member are one relationship in two directions, sharing the cycle check that is the only interesting part. A group that contains itself transitively is a repository some tools will walk forever, so the check happens before the commit rather than after.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `GroupMembershipCommand` in two forms sharing one argument type: the group identifier, the member identifier, and the direction, with both identifiers required.
3. Implement `GroupMembershipResult` as the group, the member, the direction applied, and whether the relationship already held, so a repeat is visibly a repeat.
4. Declare for `add_group_member` exactly `group_not_found`, `member_not_found`, `authorizable_kind_mismatch`, `authorizable_access_denied`, `membership_cycle_refused`, `repository_commit_failed`, `mutation_outcome_unknown`; and for `remove_group_member` exactly `group_not_found`, `member_not_found`, `authorizable_kind_mismatch`, `authorizable_access_denied`, `membership_cycle_refused`, `repository_commit_failed`, `mutation_outcome_unknown`. `membership_cycle_refused` is checked transitively rather than only directly, because a two-step cycle is as damaging as a one-step one and much easier to create by accident.
5. Implement `GroupMembershipHandler` walking the existing membership graph under the caller's session to detect a transitive cycle before the commit, and applying in one commit.

**Tests:**

- A transitive cycle several groups long is refused before the commit, proved against a fixture graph, with every group asserted unchanged.
- Adding a member that is already one and removing one that is not are both reported as already-holding rather than refused, and neither writes.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`add_group_member` at 16384 bytes; `remove_group_member` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `add_group_member` requires an operation key and a submission without one is refused; `remove_group_member` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=GroupMembershipCommandTest && ./mvnw verify -pl aem -Dtest=GroupMembershipHandlerTest && ./mvnw verify -pl interop -Dtest=GroupMembershipScenario` proves a transitive multi-step cycle refused before the commit with every group unchanged, and an already-holding relationship reported rather than refused with nothing written, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
