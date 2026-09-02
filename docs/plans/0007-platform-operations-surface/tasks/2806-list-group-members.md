---
id: list-group-members
title: "List Group Members"
workstream: "0028"
kind: task
depends_on:
  - group-membership
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/authorizable/ListGroupMembersCommand.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/MembershipScope.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/ListGroupMembersResult.java
  - core/src/main/resources/registry/list_group_members.toml
  - "schemas/commands/list_group_members/**"
  - core/src/test/java/rs/slingshot/agent/command/authorizable/ListGroupMembersCommandTest.java
  - "core/src/test/resources/fixtures/commands/list_group_members/**"
  - aem/src/main/java/rs/slingshot/agent/aem/authorizable/ListGroupMembersHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/authorizable/ListGroupMembersHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/ListGroupMembersScenario.java
  - interop/scenarios/list-group-members.toml
status: done
merged_as: ""
---
# List Group Members

Who is actually in this group, which for a nested group is a different question from who was added to it. Reporting direct and inherited membership separately is what stops an operator removing somebody who is still in through another group.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `ListGroupMembersCommand` with the group identifier, a required `MembershipScope` naming whether inherited membership is walked, a result window, and an optional continuation token.
3. Implement `ListGroupMembersResult` as each member's identifier, kind, and whether the membership is direct or inherited, with no profile property of any kind.
4. Declare exactly `discovery_budget_exceeded`, `continuation_token_malformed`, `continuation_token_integrity_invalid`, `continuation_token_wrong_target`, `continuation_token_wrong_query`, `continuation_token_expired`, `group_not_found`, `authorizable_kind_mismatch`, `authorizable_access_denied`. `authorizable_kind_mismatch` covers a user addressed where a group belongs, which is the most common way this command is misused.
5. Implement `ListGroupMembersHandler` reading direct membership and, where asked, walking inherited membership under the discovery budget, with the caller's session.

**Tests:**

- A member who is both direct and inherited is reported once as direct, and a member only inherited is reported as inherited.
- No result carries a profile property, asserted over members whose profiles hold distinctive values.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`list_group_members` at 1048576 bytes).
- The operation-key rule is proved from the row rather than restated: `list_group_members` refuses an operation key and a submission carrying one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=ListGroupMembersCommandTest && ./mvnw verify -pl aem -Dtest=ListGroupMembersHandlerTest && ./mvnw verify -pl interop -Dtest=ListGroupMembersScenario` proves direct and inherited membership distinguished with a doubly-reachable member reported once as direct, no profile property disclosed, and both sides of the discovery budget when walking inheritance, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
