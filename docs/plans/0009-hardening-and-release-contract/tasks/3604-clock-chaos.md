---
id: clock-chaos
title: "Clock Chaos"
workstream: "0036"
kind: task
depends_on:
  - platform-fault-chaos
gated: false
touches:
  - interop/src/main/java/rs/slingshot/agent/interop/harness/ClockDisruptor.java
  - support/agent-contract.toml
  - support/agent-contract.sha256
  - interop/src/test/java/rs/slingshot/agent/interop/ClockChaosScenario.java
  - interop/scenarios/clock-chaos.toml
  - development/src/test/java/rs/slingshot/agent/development/ClockUsagePolicyTest.java
status: done
merged_as: ""
---
# Clock Chaos

Every lease and every retention decision is a comparison of two instants, and the failure mode of a skewed clock is two nodes both believing they hold one lease. The property is always the same: a decision may be conservative and may never be wrong.

**Steps:**

1. Enumerate every comparison of instants in the repository — the lease and its renewal, retention, token expiry, key-ring prior retention, the stream session bound, and the missing-operation grace window.
2. Implement `ClockDisruptor` skewing one node's clock relative to another's, pausing a clock, and jumping one forward and backward, on a running cluster.
3. For each comparison under each disruption, assert the conservative direction: a lease is never held by two nodes, a token is never accepted after its expiry, and a record is never removed before its retention.
4. Assert every duration is measured on a monotonic source and every instant comparison on a wall-clock source, and that neither is used for the other's purpose — a source-policy rule rather than a convention.
5. Assert the key ring's prior retention exceeds the longest token lifetime plus the declared skew bound, so a rotation under maximum skew still strands no token.

**Tests:**

- Under maximum declared skew, no two nodes hold one lease, across the whole handover corpus.
- No token is accepted after its expiry under any disruption, and none is refused before it under a backward jump.
- No record is removed before its retention under any disruption.
- A duration measured on a wall-clock source, or an instant compared on a monotonic source, is a source-policy finding, with comment-only fixtures passing.
- The prior-retention relation is asserted from the contract at build time against the declared skew bound, and a fixture contract violating it fails.

- **Done when:** `./mvnw verify -pl interop -Dtest=ClockChaosScenario && ./mvnw verify -pl development -Dtest=ClockUsagePolicyTest` proves no double-held lease under maximum skew, no token accepted past expiry or refused before it under any disruption, no early removal, source-policy separation of monotonic and wall-clock sources with comment-only fixtures passing, and a build-time prior-retention relation against the declared skew bound.
