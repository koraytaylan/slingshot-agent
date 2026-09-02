---
id: platform-fault-chaos
title: "Platform Fault Chaos"
workstream: "0036"
kind: task
depends_on:
  - repository-fault-chaos
gated: false
touches:
  - interop/src/main/java/rs/slingshot/agent/interop/harness/PlatformFaultInjector.java
  - interop/src/test/java/rs/slingshot/agent/interop/PlatformFaultScenario.java
  - interop/scenarios/platform-fault.toml
status: done
merged_as: ""
---
# Platform Fault Chaos

The platform commands report what the platform said. What happens when the platform says nothing is the case that decides whether `platform_control_outcome_unknown` is a real answer or a category nobody can reach.

**Steps:**

1. Implement `PlatformFaultInjector` making each platform interface — job system, workflow engine, replication, user management, configuration — fail in three ways: rejecting, throwing, and never answering.
2. For each platform command, inject each fault and assert the outcome: a rejection maps to its declared rejection category, a throw maps to the same, and a non-answer maps to the unknown outcome.
3. Assert the unknown outcome is genuinely reachable for every control command, since a category no fault can produce is a category that does not exist.
4. Assert every fault leaves the platform's own state unchanged where the command had not yet acted, read back rather than inferred.
5. Assert the agent survives every fault: no fault leaves a thread held, a session leaked, a lease unreleased, or a stream slot occupied.

**Tests:**

- Every platform command produces its declared rejection category under a rejection and under a throw, with the two indistinguishable to the caller by design.
- Every control command produces the unknown outcome under a non-answer, proving the category is reachable.
- The platform's state is unchanged where the command had not acted, read back from the platform.
- No fault leaves a held thread, a leaked session, an unreleased lease, or an occupied stream slot, asserted after each.
- A non-answer is bounded by the declared deadline rather than waiting indefinitely, proved against the contract value on a monotonic clock.

- **Done when:** `./mvnw verify -pl interop -Dtest=PlatformFaultScenario` proves declared rejection categories under both rejection and throw, a reachable unknown outcome for every control command under a non-answer, unchanged platform state read back where the command had not acted, no held thread, leaked session, unreleased lease, or occupied slot after any fault, and a non-answer bounded by its contract deadline.
