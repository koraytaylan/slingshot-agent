---
id: job-snapshot-document
title: "Job Snapshot Document"
workstream: "0007"
kind: task
depends_on:
  - job-event-document
gated: false
touches:
  - schemas/agent-protocol/job/snapshot.json
  - core/src/main/java/rs/slingshot/agent/wire/JobSnapshot.java
  - core/src/test/java/rs/slingshot/agent/wire/JobSnapshotTest.java
  - "core/src/test/resources/fixtures/job-snapshot/**"
status: done
merged_as: ""
---
# Job Snapshot Document

What the store holds about one job — the record, rather than the news an event stream carries. A disconnected stream is incomplete unless something can be asked what is currently true, and that something has to answer with the same vocabulary the stream used or reconciling the two is guesswork.

**Steps:**

1. Author fixtures for a snapshot at each kind, for a snapshot whose sequence is below an event the client already saw, for one naming another generation, and for one whose kind and sequence disagree about terminality.
2. Implement `JobSnapshot` with the same closed kind set and sequence rules the event document uses, sharing the types rather than restating them.
3. Make the reconciliation property explicit and checkable: a snapshot at sequence n asserts that every event up to n has happened, so a snapshot below an already-observed event is a refusal rather than a retraction.
4. Refuse a snapshot from another generation, distinctly from a snapshot that is merely behind.
5. Prove a terminal snapshot is final: no snapshot may follow a terminal one for the same operation and generation with a different kind.

**Tests:**

- A snapshot at each kind constructs, and its terminality is the same value the event kind reports.
- A snapshot below an already-observed event sequence is refused naming both, distinctly from a foreign-generation refusal.
- A snapshot following a terminal one with a different kind is refused; one repeating the same terminal kind and sequence is accepted as idempotent.
- The kind and sequence types are asserted to be the very types the event document uses, not copies.
- The committed schema and the typed model are asserted equal in both directions.

- **Done when:** `./mvnw verify -pl core -Dtest=JobSnapshotTest` proves a snapshot at every kind with terminality equal to the event kind's, two distinct staleness refusals, terminal finality with an idempotent repeat accepted, shared rather than duplicated types, and two-way schema-to-model correspondence.
