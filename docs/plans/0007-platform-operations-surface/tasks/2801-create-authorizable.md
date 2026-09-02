---
id: create-authorizable
title: "Create an Authorizable"
workstream: "0028"
kind: task
depends_on:
  - cancel-sling-job
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/authorizable/CreateAuthorizableCommand.java
  - core/src/main/java/rs/slingshot/agent/command/authorizable/CreateAuthorizableResult.java
  - core/src/main/resources/registry/create_user.toml
  - "schemas/commands/create_user/**"
  - core/src/main/resources/registry/create_group.toml
  - "schemas/commands/create_group/**"
  - core/src/test/java/rs/slingshot/agent/command/authorizable/CreateAuthorizableCommandTest.java
  - "core/src/test/resources/fixtures/commands/create_user/**"
  - aem/src/main/java/rs/slingshot/agent/aem/authorizable/CreateAuthorizableHandler.java
  - aem/src/test/java/rs/slingshot/agent/aem/authorizable/CreateAuthorizableHandlerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/command/CreateAuthorizableScenario.java
  - interop/scenarios/create-user.toml
status: done
merged_as: ""
---
# Create an Authorizable

One task because a user and a group are one creation with one kind argument, and because splitting them would produce two implementations of the same intermediate-path handling that would drift. No password crosses this surface: a command carrying one would put it in a submission body, a durable operation record, and an event ledger.

**Steps:**

1. Commit canonical accepted and refused argument fixtures and exact no-effect failure documents before the implementation, one line per vector, each carrying the note that says what it proves.
2. Implement `CreateAuthorizableCommand` with the kind, the identifier, an optional intermediate path, and the initial profile properties, and no credential member of any kind.
3. Implement `CreateAuthorizableResult` as the created authorizable's identifier, its kind, and the path it was created at, so a caller sees where an intermediate path actually put it.
4. Declare for `create_user` exactly `authorizable_already_exists`, `identifier_rejected`, `intermediate_path_rejected`, `property_rejected`, `authorizable_access_denied`, `repository_commit_failed`, `mutation_outcome_unknown`; and for `create_group` exactly `authorizable_already_exists`, `identifier_rejected`, `intermediate_path_rejected`, `property_rejected`, `authorizable_access_denied`, `repository_commit_failed`, `mutation_outcome_unknown`. `intermediate_path_rejected` is distinct from `identifier_rejected` because one is a caller putting an account somewhere the platform will not accept and the other is a caller choosing a name it will not accept.
5. Implement `CreateAuthorizableHandler` creating through the platform's own user management under the caller's session, in one commit, with the account created without a password.

**Tests:**

- The command type is asserted to expose no credential member of any kind, and the source policy refuses one being added.
- A user is created without a password and is asserted unable to authenticate until an operator sets one outside this agent.
- Every accepted vector round-trips byte-identically and every refused one is refused with its own category, with no category outside the declared set reachable.
- The result bound is proved at exactly the registry row's value and one byte past it, where past it becomes an artifact reference rather than a truncation (`create_user` at 16384 bytes; `create_group` at 16384 bytes).
- The operation-key rule is proved from the row rather than restated: `create_user` requires an operation key and a submission without one is refused; `create_group` requires an operation key and a submission without one is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CreateAuthorizableCommandTest && ./mvnw verify -pl aem -Dtest=CreateAuthorizableHandlerTest && ./mvnw verify -pl interop -Dtest=CreateAuthorizableScenario` proves a type with no credential member enforced by the source policy, an account created without a password and unable to authenticate, and distinct identifier and intermediate-path refusals, every declared failure with no undeclared category reachable, both sides of the result bound with overflow published rather than truncated, and the row's own operation-key rule.
