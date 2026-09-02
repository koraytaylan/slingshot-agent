---
id: cluster-handover-chaos
title: "Cluster Handover Chaos"
workstream: "0036"
kind: task
depends_on:
  - command-argument-fuzzing
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/ClusterHandoverScenario.java
  - interop/scenarios/cluster-handover.toml
  - support/interop-harness.toml
status: done
merged_as: ""
---
# Cluster Handover Chaos

An Adobe Experience Manager as a Cloud Service author is not one machine. Work moves, instances stop, and the repository underneath is shared — so every property about contention has to be proved with two nodes rather than one, because a single node cannot contend with itself in the way that matters.

**Steps:**

1. Drive the harness's two-node arrangement, which Plan 0001 built because the store's own contention properties needed it, rather than a second one here — so both nodes see the same store and neither is a simulation of the other.
2. Enumerate the handover points: while one node holds a lease, at the moment a lease expires, while a job is being redelivered, during a sweep, and during a generation rotation.
3. For each, stop the node holding the work without a graceful shutdown and assert the other node's behaviour: it waits while the lease is live, takes it exactly once after expiry, and never produces a second effect.
4. Assert the store's invariants after every handover — one logical operation, exactly one effect for every committed admission, snapshot equal to fold, no terminal record without its terminal event, capacity equal to contents in total and per caller — using the same verification pass the sweep uses.
5. Assert both nodes agree about what happened: a lookup on either returns the same terminal answer for every operation.

**Tests:**

- Both nodes share one repository and one store, proved by writing on one and reading on the other.
- At each handover point, exactly one effect is observed for every committed admission, counted by a marker the execution writes once, including where recovery on the surviving node is what delivered it.
- A node waits while another's lease is live and takes it exactly once after expiry, with a two-node race producing one holder.
- Every store invariant holds after every handover point.
- A lookup on either node returns the same terminal answer for every operation, compared byte for byte.

- **Done when:** `./mvnw verify -pl interop -Dtest=ClusterHandoverScenario` proves two instances on one shared repository, exactly one effect per committed admission at every enumerated handover point including where the surviving node's recovery delivered it, waiting while a lease is live and a single winner after expiry, every store invariant holding after each handover, and byte-identical terminal answers from both nodes.
