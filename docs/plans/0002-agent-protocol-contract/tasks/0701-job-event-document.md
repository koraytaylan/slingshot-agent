---
id: job-event-document
title: "Job Event Document"
workstream: "0007"
kind: task
depends_on:
  - envelope-and-error-documents
gated: false
touches:
  - schemas/agent-protocol/job/event.json
  - core/src/main/java/rs/slingshot/agent/wire/JobEvent.java
  - core/src/main/java/rs/slingshot/agent/wire/JobEventKind.java
  - core/src/main/java/rs/slingshot/agent/wire/EventSequence.java
  - core/src/test/java/rs/slingshot/agent/wire/JobEventTest.java
  - "core/src/test/resources/fixtures/job-event/**"
status: done
merged_as: ""
---
# Job Event Document

One thing the agent says happened to one job. The kinds are closed: a client meeting one it did not know would have to guess whether it mattered, and both answers are wrong — an unknown terminal kind treated as progress waits forever, an unknown progress kind treated as terminal reports an outcome that has not happened.

**Steps:**

1. Author fixtures for each of the five kinds, for an unknown kind, for a sequence of zero, for a decreasing sequence, and for an event exceeding the per-operation event bound.
2. Implement `JobEventKind` as exactly `accepted`, `started`, `progress`, `succeeded`, and `failed`, with terminality a property of the kind rather than a separate field, so nothing can describe a kind as terminal and not.
3. Implement `EventSequence` starting at zero and strictly increasing within one operation and generation, refusing a repeat and a decrease as two distinct failures.
4. Implement `JobEvent` carrying the operation identifier, the generation, the kind, and the sequence, with the per-operation event count and byte bounds read from `AgentContract`.
5. Refuse an event whose generation is not the one the store is serving, because an event from another incarnation is an event about a different durable thing.

**Tests:**

- Each of the five kinds constructs and reports its terminality; an unknown kind is refused naming it.
- A sequence of zero is accepted; a repeat and a decrease are two distinct refusals naming both values.
- The per-operation event count and byte bounds are each proved at exactly the limit and one past it.
- An event naming another generation is refused naming both generations.
- The committed schema's kind set and the typed set are asserted equal in both directions.

- **Done when:** `./mvnw verify -pl core -Dtest=JobEventTest` proves five kinds with intrinsic terminality, an unknown kind refused, two distinct sequence refusals, both sides of both per-operation bounds, refusal of a foreign generation, and two-way schema-to-model correspondence.
