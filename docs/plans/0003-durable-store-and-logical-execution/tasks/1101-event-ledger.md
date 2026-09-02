---
id: event-ledger
title: "Event Ledger"
workstream: "0011"
kind: task
depends_on:
  - sling-job-consumer
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/EventLedger.java
  - core/src/main/java/rs/slingshot/agent/store/LedgerAdmission.java
  - core/src/test/java/rs/slingshot/agent/store/EventLedgerTest.java
  - "core/src/test/resources/fixtures/event-ledger/**"
status: done
merged_as: ""
---
# Event Ledger

Append-only, sequenced, and bounded in two directions at once — per operation, because one runaway command must not fill the store, and per generation, because the store as a whole has a size somebody has to have chosen.

**Steps:**

1. Author fixtures for an append at sequence zero, for a gap, for a repeat, for appends at and one past the per-operation bound, and for an append refused by generation capacity.
2. Implement `EventLedger` writing each event at a path derived from its sequence, so appending is a claim and two writers cannot both take one sequence.
3. Refuse a gap and refuse a repeat as two distinct failures, since a gap means an event was lost and a repeat means a writer replayed, and neither may present as the other.
4. Admit against capacity before writing, through the capacity ledger and never through a counter of its own, so `LedgerAdmission` is this ledger's use of that authority rather than a second one; make refusal by admission a distinct outcome from a write that failed — one needs a sweep, the other needs investigation.
5. Enforce the per-operation event count and byte bounds and the per-generation row and byte bounds, all four read from the contract and none written here, and declare each as an accounted quantity the ledger already knows rather than as a bound this task compares itself.

**Tests:**

- A first append at sequence zero succeeds; a gap and a repeat are two distinct refusals naming the sequences.
- Concurrent appends at the same sequence produce exactly one event.
- All four bounds are proved at exactly the limit and one past it.
- Admission refusal is distinct from write failure, proved by a store at capacity and by an injected write fault.
- Every admission is proved to go through the capacity ledger, asserted over the type, with no counter incremented or bound compared here.
- The counters after a series of appends are asserted equal to the events actually present, proved by reading the tree.

- **Done when:** `./mvnw verify -pl core -Dtest=EventLedgerTest` proves sequenced append with exactly one winner under concurrency, distinct gap and repeat refusals, both sides of all four bounds read from the contract, admission refusal distinct from write failure taken only through the capacity ledger, and counters equal to the tree.
