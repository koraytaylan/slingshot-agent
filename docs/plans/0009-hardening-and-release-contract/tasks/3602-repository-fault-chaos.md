---
id: repository-fault-chaos
title: "Repository Fault Chaos"
workstream: "0036"
kind: task
depends_on:
  - cluster-handover-chaos
gated: false
touches:
  - interop/src/main/java/rs/slingshot/agent/interop/harness/RepositoryFaultInjector.java
  - interop/src/test/java/rs/slingshot/agent/interop/RepositoryFaultScenario.java
  - interop/scenarios/repository-fault.toml
status: done
merged_as: ""
---
# Repository Fault Chaos

Every durable guarantee in this repository is a claim about what a commit does. A commit that fails, one that conflicts, and one that cannot happen because the store is full are three different things, and code that treats them alike is code whose recovery is wrong for two of them.

**Steps:**

1. Implement `RepositoryFaultInjector` producing four faults at a named point: a commit that fails, a commit that conflicts, a repository that refuses a write because it is full, and a session that is invalidated mid-operation.
2. Enumerate the injection points across the store: the admission claim, the outbox claim, the lease claim, each state transition, each event append, each artifact write, and the terminal commit.
3. For each fault at each point, assert the store's invariants hold afterwards and the operation reaches a disposition rather than a state nothing will move it from.
4. Assert the four faults produce four distinguishable outcomes: a retryable failure, a contention retry, an admission refusal, and an unknown outcome, never one standing in for another.
5. Assert a full store refuses admission rather than failing a write, and that a sweep afterwards restores admission exactly.

**Tests:**

- Each of the four faults at each enumerated point leaves every store invariant holding.
- No fault leaves an operation in a state no path will move it from, proved by running recovery afterwards and asserting a disposition.
- The four faults are distinguishable in the recorded outcome, with none reported as another.
- A full store refuses admission before writing, and a sweep restores admission with capacity equal to contents afterwards.
- A conflicting commit is retried within the declared bound and reported as contention past it, proved at exactly the bound and one past.

- **Done when:** `./mvnw verify -pl interop -Dtest=RepositoryFaultScenario` proves every store invariant after all four faults at every enumerated point, a reachable disposition after each, four distinguishable outcomes with no substitution, admission refused rather than a failed write on a full store with exact restoration after a sweep, and both sides of the contention retry bound.
