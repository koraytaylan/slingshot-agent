---
id: worker-execution-fence
title: "Worker Execution Fence"
workstream: "0010"
kind: task
depends_on:
  - outbox-and-physical-attempts
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/ExecutionFence.java
  - core/src/main/java/rs/slingshot/agent/execution/FenceHolder.java
  - core/src/main/java/rs/slingshot/agent/execution/FenceOutcome.java
  - core/src/test/java/rs/slingshot/agent/execution/ExecutionFenceTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/FenceHandoverScenario.java
  - interop/scenarios/fence-handover.toml
status: done
merged_as: ""
---
# Worker Execution Fence

At most one worker executing at any instant, and "at most one" has to survive a node disappearing mid-execution. A worker that loses the fence stops without finishing, because finishing would be the second effect the whole design exists to prevent.

**Steps:**

1. Author fixtures for a fence taken, a fence refused while live, a fence taken after expiry, a renewal that succeeds, a renewal that fails against a changed holder, and two workers racing for an expired fence.
2. Implement `ExecutionFence` claiming the lease by creation with a holder and an expiry a lease-duration ahead, both read from the contract.
3. Implement renewal as a compare-and-set against the holder's own record, at the contract's renewal interval, and assert the interval leaves at least two missed renewals of margin before another worker may take over — so an ordinary pause is not a handover.
4. Make a lost fence stop the worker immediately and write nothing further, with `FenceOutcome` distinguishing lost from expired from contended, since the three mean different things to whoever reads the record afterwards.
5. Permit takeover only by compare-and-set against the exact expired record the taker read, so two workers racing on an expired lease both compare the same record and exactly one write succeeds.

**Tests:**

- A fence is taken once; a second attempt while it is live is refused, and after expiry it is taken.
- Renewal against the holder's own record succeeds; against a changed holder it fails and the outcome is lost rather than contended.
- The renewal interval is asserted to be at most a third of the lease, read from the contract and not written here.
- Two workers racing for an expired fence produce exactly one holder, proved on the harness's two-node arrangement against one shared repository rather than on two sessions of one instance, since one instance cannot contend the way the cluster this runs on does.
- A worker that has lost the fence is proved to write nothing further, by asserting the store is unchanged after it observes the loss.

- **Done when:** `./mvnw verify -pl core -Dtest=ExecutionFenceTest && ./mvnw verify -pl interop -Dtest=FenceHandoverScenario` proves single holding with refusal while live and takeover after expiry, renewal succeeding and failing distinctly, a renewal interval at most a third of the lease read from the contract, exactly one winner in a two-node race against one shared repository, and a store unchanged by a worker that lost the fence.
