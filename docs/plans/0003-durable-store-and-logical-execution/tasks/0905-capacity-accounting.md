---
id: capacity-accounting
title: "Capacity Accounting"
workstream: "0009"
kind: task
depends_on:
  - durable-key-ring
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/CapacityLedger.java
  - core/src/main/java/rs/slingshot/agent/store/AccountedQuantity.java
  - core/src/test/java/rs/slingshot/agent/store/CapacityLedgerTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/CapacityAdmissionScenario.java
  - interop/scenarios/capacity-admission.toml
status: done
merged_as: ""
---
# Capacity Accounting

The store's size is a number somebody chose, and admitting work against it has to be correct when two nodes admit at the same instant. A read, an addition, and a write is exactly the arrangement in which both see the old total.

It sits with the store primitives rather than with the ledgers because everything after it admits against these counts — the operation record, the event ledger, the subscription ledger, the artifact store, and the intake a manifest declares. An authority that arrived after its callers would be an authority several of them had already written their own version of, and two admission paths over one count is exactly the arrangement in which the total stops meaning anything.

A total on its own is not enough. Every caller past authentication shares one store, so a bound that is only a total is a bound one caller can spend on everybody else's behalf — and an agent that stopped admitting because one client was busy is indistinguishable, from every other client, from one that is broken.

**Steps:**

1. Author fixtures for every accounted quantity the contract bounds, for admission at and one past each total bound and each per-caller bound, for concurrent admission at each boundary, for a reservation released without being consumed, and for an admission refused at the total while a second caller is still under theirs.
2. Implement `AccountedQuantity` as the closed set of quantities the contract bounds, so a quantity with no bound and a bound with no quantity both fail. Executions in flight is one of them, and it is the only quantity here that is not about storage at all. A command runs inside the request that submitted it, so an execution in flight is a request thread this agent is holding in somebody else's author — the resource whose exhaustion is indistinguishable, from outside, from an instance that has gone. A bound on what the store keeps with no bound on what it is doing is a bound on the wrong thing.
3. Implement `CapacityLedger` over `ShardedCount`, admitting by advancing a shard and checking the resulting total rather than by checking and then advancing — and releasing the advance exactly when the check refuses, so a refused admission leaves the count where it found it rather than permanently above the bound. Compare against the declared bound less the in-flight margin the count declares, so a race between nodes refuses early rather than admitting past the bound.
4. Account every quantity twice: once against the per-generation total and once against a count derived from the submitting caller, and refuse at whichever is reached first, naming which. The per-caller counts are collected by the sweep on the same retention as the work they accounted for, so a caller who stops submitting stops occupying anything.
5. Support reservation for work that will produce bytes later — an artifact whose size is declared before it is written, or an intake slot whose byte count a manifest declared — with release on a path that does not complete, so a failed or abandoned command does not permanently consume capacity.
6. Report a refusal naming the quantity, the bound, whether it was the total or the caller's share, and the value that crossed it, and make it distinct from every write failure.
7. Make this the only admission path: nothing else in the repository increments an accounted counter or compares one against a bound, which the source policy's second-declaration rule enforces over the module.

**Tests:**

- Every accounted quantity is admitted at exactly its total bound and refused one past it, and at exactly its per-caller bound and refused one past it, with the refusal naming which bound was reached.
- A refused admission is proved to leave the counter unchanged, by reading it back after refusals at both bounds.
- A caller at their own bound is refused while a second caller is admitted, proving one caller cannot spend the store on everybody's behalf.
- Two nodes admitting concurrently at each boundary never take the total past the bound, proved on the harness's two-node arrangement against one shared repository, with the refusal falling at the bound less the declared margin rather than after crossing it.
- A reservation released without being consumed restores both counts exactly, and one consumed does not.
- The accounted quantity set and the contract's bound set are asserted equal in both directions, and every quantity is asserted to have both a total and a per-caller bound.
- The counts after a mixed workload equal the store's actual contents, per caller and in total, proved by a verification pass over the tree once every in-flight advance has settled.
- No admission path exists outside this type, asserted over the module, and a fixture incrementing an accounted counter elsewhere is refused.

- **Done when:** `./mvnw verify -pl core -Dtest=CapacityLedgerTest && ./mvnw verify -pl interop -Dtest=CapacityAdmissionScenario` proves both sides of every total and every per-caller bound with the reached bound named, a refused admission that leaves the count unchanged, a second caller admitted while the first is at their share, a two-node boundary race that never crosses any bound, exact reservation release across both counts, two-way quantity-to-bound correspondence with both bounds present for every quantity, counts equal to the tree after a mixed workload, and no admission path anywhere outside this type.
