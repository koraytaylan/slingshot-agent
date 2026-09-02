---
id: read-only-and-index-coverage
title: "Read-Only Enforcement and Index Coverage"
workstream: "0017"
kind: task
depends_on:
  - caller-context-and-budgets
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/ReadOnlyResolver.java
  - core/src/main/java/rs/slingshot/agent/command/DeclaredQuery.java
  - policy/query-index-coverage.toml
  - policy/design-patterns.toml
  - development/src/main/java/rs/slingshot/agent/development/IndexCoverage.java
  - development/src/test/java/rs/slingshot/agent/development/IndexCoverageTest.java
  - "development/src/test/resources/fixtures/index-coverage/**"
  - core/src/test/java/rs/slingshot/agent/command/ReadOnlyResolverTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/QueryTraversalScenario.java
  - interop/scenarios/query-traversal.toml
status: done
merged_as: ""
---
# Read-Only Enforcement and Index Coverage

Two guarantees that are only guarantees if a machine checks them. A read command that writes three frames down through a helper is the one that gets past review; a query no index covers is answered by walking the repository, which is how one command takes an author instance down.

**Steps:**

1. Author fixtures for a read handler that commits directly, one that commits through a helper, a declared query covered by an index, one covered by none, and one whose declared shape and issued shape differ.
2. Implement `ReadOnlyResolver` as a wrapper the framework gives every `Read` command, refusing a commit, a refresh that would discard, and every mutating adaptation, so the access class becomes a property of the machinery. What `Read` claims is precise and worth stating precisely: the command replaces nothing the caller owns. Scratch space inside the agent's own tree is a different tree, written by framework-owned code under the service user through the staging area rather than by the handler, and a read command that has one still cannot reach the caller's repository with anything but this resolver.
3. Implement `DeclaredQuery` so every query a handler issues is declared as data — its statement shape, its roots, and the properties it filters on — and a query issued that was not declared is refused at run time. Before executing one, ask the platform for its plan and refuse a plan that traverses, with the command's own discovery-budget category and before a single node is examined. The build-time check is a claim about the indexes a deployment row was declared to provide; this is the same claim tested against the indexes the instance in front of it actually has, which is the only version of it a customer's author is protected by — they can remove an index, and a Cloud Service environment's index set is theirs to change.
4. Write `policy/query-index-coverage.toml` naming the indexes each deployment row already provides, and implement the coverage check comparing every declared query against them, failing the build when none covers one. The file is what the build checks against; the plan the instance returns is what run time checks against; a disagreement between them is a deployment whose indexes are not what this build was told, and it is reported as itself.
5. Ship no index definition. A custom index lives outside `/apps`, changes the shape of somebody else's repository, and is an operator's decision rather than a side effect of installing an agent — so a command needing one does not ship.

**Tests:**

- A read handler that commits directly and one that commits through a helper are both refused, at run time, with the same refusal.
- A read handler holding a staging area is refused a commit through its resolver just as one without it is, and the staging write is proved to reach only the agent's own tree, so the two are never confused.
- Every declared query is covered by an index the policy names for every deployment row; a query covered by none fails the build naming the query and the rows.
- An issued query that was not declared is refused at run time naming it.
- A declared query whose plan traverses on the instance in front of it is refused before any node is examined, proved by removing the covering index from a running instance and asserting the refusal, the category, and that the platform's own statistics record no traversal.
- The built content packages are asserted to contain no index definition, over the produced archives.
- On a running instance, every declared query is executed and asserted not to have traversed, read from the platform's own query statistics rather than inferred, and asserted to have had its plan checked before execution rather than after.

- **Done when:** `./mvnw verify -pl core -Dtest=ReadOnlyResolverTest && ./mvnw verify -pl development -Dtest=IndexCoverageTest && ./mvnw verify -pl interop -Dtest=QueryTraversalScenario` proves a read commit refused directly and through a helper and refused equally for a staging-holding handler whose staging write reaches only the agent's own tree, build-time index coverage for every declared query across every deployment row, run-time refusal of an undeclared query and of a declared one whose plan traverses on the instance in front of it, no index definition in any shipped package, and no traversal observed from the platform's own statistics.
