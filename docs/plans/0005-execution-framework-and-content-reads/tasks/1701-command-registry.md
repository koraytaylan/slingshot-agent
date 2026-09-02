---
id: command-registry
title: "Command Registry"
workstream: "0017"
kind: task
depends_on: []
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/CommandRegistry.java
  - core/src/main/java/rs/slingshot/agent/command/RegistryRow.java
  - core/src/main/java/rs/slingshot/agent/command/AccessClass.java
  - core/src/main/java/rs/slingshot/agent/command/ExecutionClass.java
  - core/src/main/java/rs/slingshot/agent/command/package-info.java
  - core/src/test/java/rs/slingshot/agent/command/CommandRegistryTest.java
  - "core/src/test/resources/fixtures/command-registry/**"
  - policy/commands/README.md
  - policy/design-patterns.toml
status: done
merged_as: ""
---
# Command Registry

One file per command rather than one shared list. A shared list is a file every command task has to edit, which turns a footprint rule into a queue and makes sixty independent pieces of work into one sequence.

**Steps:**

1. Author fixtures for a well-formed row, a row missing each member, a duplicate wire name, a row whose declared access class and operation-key requirement disagree, a row declaring a staging budget of zero, and a directory whose rows are out of wire order.
2. Implement `RegistryRow` holding the wire name, the semantic contract version, the access class, whether an operation key is required, the result bound, the declared failure categories, both schema digests, the staging byte budget the command needs inside the agent's own tree — zero for the commands that need none, which is all but one — and the execution class, every member required. The staging budget and the execution class are this side's own and are not compared against the client's table, because both are facts about how this agent executes a command rather than about the contract the two halves share.
3. Implement `CommandRegistry` reading every file under the registry directory, refusing a duplicate wire name and presenting the rows in wire order however the files were found, so two builds enumerate identically.
4. Enforce the client's own rule between access class and operation key: a command that is not intrinsically idempotent requires a key and one that is refuses it, and a row disagreeing with itself is refused.
5. Implement `ExecutionClass` as a closed two-valued choice. `Immediate` means the command runs inside the request that submitted it, on that request's own session, which is what makes running as the caller free rather than granted. `Deferred` means it runs later, in a job, which requires an answer to the question of whose identity it runs under — and since this build has no such answer, a `Deferred` row is refused by the conformance gate until one is declared. Every row this product ships is `Immediate`; the class exists so that adding the first command that cannot finish inside a request is a decision somebody makes deliberately rather than a property they inherit.
6. Derive the five-field contract identity for each row from its own members, so an identity is never assembled from anything a caller supplied.

**Tests:**

- A well-formed row loads; each missing member is refused distinctly; a duplicate wire name is refused naming it.
- Rows are presented in wire order regardless of file enumeration order, proved by a fixture directory read twice in different orders.
- A row whose access class and key requirement disagree is refused naming both.
- A row declaring a staging budget is proved to yield a context carrying a staging area and one declaring none is proved to yield a context without one, so the budget is what decides it rather than the command's name.
- Every shipped row is asserted `Immediate`, and a fixture row declaring `Deferred` is refused naming the identity question it has not answered.
- The derived identity for each row equals the identity computed from its committed schemas, and a row whose digest does not match its schema is refused.
- An empty registry directory loads to an empty registry rather than failing, since Plan 0001's build has no commands.

- **Done when:** `./mvnw verify -pl core -Dtest=CommandRegistryTest` proves a complete row with distinct refusals for every missing member and a duplicate name, order-independent wire ordering, refusal of an access-class and key disagreement, a staging budget that decides whether a context carries a staging area, every shipped row immediate with a deferred one refused, identity derived from committed schemas with a mismatch refused, and an empty directory yielding an empty registry.
