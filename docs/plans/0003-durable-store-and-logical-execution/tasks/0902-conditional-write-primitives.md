---
id: conditional-write-primitives
title: "Conditional Write Primitives"
workstream: "0009"
kind: task
depends_on:
  - agent-state-layout
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/ClaimByCreation.java
  - core/src/main/java/rs/slingshot/agent/store/CompareAndSet.java
  - core/src/main/java/rs/slingshot/agent/store/ShardedCount.java
  - core/src/main/java/rs/slingshot/agent/store/WriteOutcome.java
  - core/src/test/java/rs/slingshot/agent/store/ConditionalWriteTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/ConcurrentWriteScenario.java
  - interop/scenarios/concurrent-write.toml
status: done
merged_as: ""
---
# Conditional Write Primitives

Two primitives and no third. There is no lock held across a request, because a lock held by a process that stopped is a lock nobody can take, and the failure mode of a stale lock in a cluster is an agent that answers nothing until somebody restarts it.

Counting is the place where a third primitive looks tempting and is wrong. Oak's atomic counter mixin increments without a read-modify-write, which is exactly what accounting seems to want — but on a clustered document store its increments are consolidated by a background task, so the value a node reads back is eventually consistent rather than current. An admission decision taken on it would be correct on one instance and quietly wrong on a cluster, which is the worst shape a defect can have: it passes on the tier and fails on the customer's author. So counting is compare-and-set like everything else, and the mixin is refused.

**Steps:**

1. Author fixtures for a claim that succeeds, a claim against an existing node, a compare-and-set against the expected value, one against a changed value, a counter advanced from several sessions, and a counter advanced concurrently from two cluster nodes.
2. Implement `ClaimByCreation` to add a node at a path and report exactly two outcomes — claimed, or already held — treating the underlying existence failure as the second rather than as an error.
3. Implement `CompareAndSet` to read, write, and commit under a conflict resolution that fails rather than merges, retrying a bounded number of times and reporting contention distinctly from a value that did not match.
4. Implement `ShardedCount` over compare-and-set rather than over the atomic counter mixin, and refuse the mixin anywhere in the repository as a source-policy rule with the reason recorded. A count is a declared number of sibling shard properties; a writer advances one shard by compare-and-set against the value it read, and the total is the sum of the shards read together. Sharding is what keeps a single hot property from serialising every writer in the cluster; compare-and-set is what makes each shard exact. Two nodes advancing different shards from one read can each admit, so the total may be understated by at most one advance per shard while they are in flight — which is why the bound an admission compares against is the declared bound less that margin. A decision may be conservative and may never be wrong.
5. Make `WriteOutcome` a closed set with no ambiguous member, and refuse any caller path that treats "already held" as a failure, since claiming a thing somebody else claimed is the ordinary case this design is built around.

**Tests:**

- A claim against a free path succeeds and against a held path reports already-held, and neither raises.
- A compare-and-set against the expected value writes; against a changed value it does not write, proved by reading back the unchanged value.
- Contention and value-mismatch are two distinct outcomes, and the retry bound is proved at exactly the declared count and one past it.
- The atomic counter mixin appears nowhere, asserted over the built bundles as well as the source, and a fixture using it is refused with the reason named.
- Concurrent advances from several sessions total exactly the number of advances, proved on the harness's two-node arrangement against one shared repository rather than in a single process or against a single instance, since a single instance cannot exhibit the failure this primitive exists to avoid.
- The shard total is proved never to overstate the contents, and to understate them by at most one advance per shard while advances are in flight, across a two-node race at the boundary.
- Concurrent claims of one path from two nodes succeed exactly once.

- **Done when:** `./mvnw verify -pl core -Dtest=ConditionalWriteTest && ./mvnw verify -pl interop -Dtest=ConcurrentWriteScenario` proves two claim outcomes with no raise, a non-writing failed compare-and-set, distinct contention and mismatch outcomes at both sides of the retry bound, no use of the atomic counter mixin anywhere in source or bundle, and on two nodes sharing one repository exactly one successful claim, an exact concurrent total, and a shard sum that never overstates the contents.
