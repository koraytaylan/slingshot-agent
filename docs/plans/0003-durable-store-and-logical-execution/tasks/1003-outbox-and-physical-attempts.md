---
id: outbox-and-physical-attempts
title: "Outbox and Physical Attempts"
workstream: "0010"
kind: task
depends_on:
  - submission-idempotency
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/Outbox.java
  - core/src/main/java/rs/slingshot/agent/execution/PhysicalAttempt.java
  - core/src/test/java/rs/slingshot/agent/execution/OutboxTest.java
  - "core/src/test/resources/fixtures/outbox/**"
status: done
merged_as: ""
---
# Outbox and Physical Attempts

Sling delivers a job at least once, a cluster can move it, and a node can stop holding one. A duplicate physical record is not a defect to be prevented; it is the normal case, and the design's job is to make it harmless and visible.

**Steps:**

1. Author fixtures for a first attempt, for a duplicate physical identifier, for attempts up to and one past the bound, and for an attempt recorded against a terminal operation.
2. Implement `PhysicalAttempt` recording the physical job identifier, the node that observed it, and when, bounded by the contract's Sling job identifier limit.
3. Implement `Outbox` to claim one node per attempt, so recording an attempt is atomic and a duplicate arrival is observed rather than counted twice.
4. Refuse a new attempt past the contract's attempt bound, and make exhaustion a recorded fact on the operation rather than a condition somebody recomputes by counting children.
5. Record an attempt against an already-terminal operation without changing the outcome, because a job system that redelivers after completion is normal and its redelivery is information rather than a problem.

**Tests:**

- A first attempt is recorded; the same physical identifier arriving again is observed as a duplicate and adds no second row.
- Attempts are accepted at exactly the bound and refused one past it, and exhaustion is recorded on the operation.
- An attempt against a terminal operation is recorded and the outcome is asserted unchanged.
- The physical identifier is accepted at exactly its byte bound and refused one past it.
- Concurrent recording of the same attempt from two sessions produces exactly one row.

- **Done when:** `./mvnw verify -pl core -Dtest=OutboxTest` proves duplicate physical identifiers collapse to one row including under concurrency, both sides of the attempt bound with exhaustion recorded rather than counted, an unchanged outcome on post-terminal redelivery, and both sides of the identifier byte bound.
