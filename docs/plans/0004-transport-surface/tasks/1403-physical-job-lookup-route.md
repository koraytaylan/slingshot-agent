---
id: physical-job-lookup-route
title: "Physical Job Lookup Route"
workstream: "0014"
kind: task
depends_on:
  - operation-lookup-route
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/http/PhysicalJobServlet.java
  - core/src/test/java/rs/slingshot/agent/http/PhysicalJobServletTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/PhysicalJobScenario.java
  - interop/scenarios/physical-job.toml
status: done
merged_as: ""
---
# Physical Job Lookup Route

Several physical records for one logical operation is the normal case, and a client diagnosing a stuck operation needs to see them. What it must not see is anything about the job system's own contents beyond the records this operation owns.

**Steps:**

1. Author fixtures for an operation with no attempts, with one, with several, with more than the match bound, and for a physical identifier belonging to another operation.
2. Implement `PhysicalJobServlet` to answer from the outbox alone — the attempts this operation recorded — and never by asking the job system what it holds.
3. Bound the answer at the contract's physical-match limit and say the answer was bounded rather than silently truncating it.
4. Refuse a lookup by a physical identifier that belongs to another operation with the same answer as an unknown one.
5. Disclose nothing about the queue: no queue name, no topic, no other operation's identifier, no transport address.

**Tests:**

- Zero, one, and several attempts each answer exactly, and the answer is proved to come from the outbox rather than from the job system, by a fixture whose job system holds records the outbox does not.
- The match bound is proved at exactly the limit and one past it, with the over-bound answer marked as bounded.
- A foreign physical identifier produces a response byte-identical to an unknown one.
- No response contains a queue name, a topic, another operation's identifier, or a transport address, asserted over a corpus.
- On a running instance, redelivering a job adds an attempt visible here and does not change the operation's outcome.

- **Done when:** `./mvnw verify -pl core -Dtest=PhysicalJobServletTest && ./mvnw verify -pl interop -Dtest=PhysicalJobScenario` proves outbox-only answers that ignore job-system contents, both sides of the match bound with explicit boundedness, byte-identical foreign and unknown responses, no queue or transport disclosure, and a redelivery visible as an attempt with an unchanged outcome.
