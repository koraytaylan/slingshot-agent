---
id: mutation-interop-proof
title: "Mutation Interop Proof"
workstream: "0024"
kind: task
depends_on:
  - replicate-content
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/MutationSafetyScenario.java
  - interop/scenarios/mutation-safety.toml
  - development/src/main/java/rs/slingshot/agent/development/MutationCoverage.java
  - development/src/test/java/rs/slingshot/agent/development/MutationCoverageTest.java
status: done
merged_as: ""
---
# Mutation Interop Proof

Each command's own scenario proves that command. This proves the properties that are only interesting across all of them, and it is the one that would catch the twentieth command quietly behaving unlike the first nineteen.

It selects by what a row declares rather than by where its handler lives, because the registry grows: this plan's commands change the repository, one of them changes nothing and offers something, and the next plan's change a platform that is not the repository. A suite that selected every `Write` row would demand a repository commit from a bundle being stopped, and one that selected by package would stop selecting the day somebody moved a class.

**Steps:**

1. Enumerate from the registry directory rather than from a list written here, by what each row declares: a row declaring `mutation_outcome_unknown` changes the caller's repository, and a row declaring `admission_outcome_unknown` offers something to a machine this agent cannot observe. Both are covered here; each gets the properties that are true of it.
2. For every repository-mutating row, drive every declared failure category that can be produced without a fault injector, and assert the repository is byte-identical before and after by comparing a serialised view of the affected subtree.
3. For every repository-mutating row, count the repository commits made during a successful execution and assert exactly one; for every admission row, assert the handler's own session made none, which is a claim about what this build wrote and not about what the machinery it called did on its own account.
4. For each, interrupt the container mid-execution and assert that the affected subtree afterwards is either the pre-state or the post-state and never a mixture.
5. For each, resend the same submission under the same operation identifier and assert the repository is unchanged by the second submission and that an admission is offered once rather than twice, which is Plan 0003's one-effect guarantee observed from outside the store.
6. Implement `MutationCoverage` to assert the registry partitions cleanly, reading which categories each cross-cutting scenario claims from the scenario inventory rather than from anything written here — so the plan that adds a third category and its own proof needs no edit to this check. Every row declares at most one of `mutation_outcome_unknown`, `admission_outcome_unknown`, and `platform_control_outcome_unknown`; every row declaring one is claimed by exactly one cross-cutting scenario; and a row claimed by none and a row claimed by two are two distinct findings, so a command can never be unproved because two suites each assumed the other had it.

**Tests:**

- Every repository-mutating row and every admission row is covered; a row with no coverage fails the check naming it.
- Every reachable declared failure leaves a byte-identical subtree; a fixture command that leaves a stray node is detected.
- Every repository-mutating success makes exactly one commit, counted from the platform rather than from this build's own wrapper, and every admission makes none through the handler's own session.
- Every interrupted execution leaves the pre-state or the post-state, across a corpus of interruption points.
- Every resend leaves the repository unchanged and offers nothing a second time, and the operation store shows one logical operation with several attempts.
- No registry row declares two of the three outcome categories, no row declaring one is claimed by two scenarios or by none, and a fixture row in each of those three shapes is detected distinctly.
- The claimed categories are proved read from the scenario inventory, by a fixture scenario claiming a category and expecting the partition to change with no edit to the check.

- **Done when:** `./mvnw verify -pl interop -Dtest=MutationSafetyScenario && ./mvnw verify -pl development -Dtest=MutationCoverageTest` proves coverage of every repository-mutating and every admission registry row derived from the directory, a byte-identical subtree after every reachable failure and after a successful admission, exactly one platform-counted commit per mutation and none through an admission's own session, all-or-nothing state after every interruption point, an unchanged repository and a single offer under resend, and a registry that partitions into exactly one cross-cutting scenario per declaring row — read from the scenario inventory — with unclaimed and doubly-claimed rows both detected.
