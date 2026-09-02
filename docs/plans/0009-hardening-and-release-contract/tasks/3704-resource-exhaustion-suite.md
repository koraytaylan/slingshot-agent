---
id: resource-exhaustion-suite
title: "Resource Exhaustion Suite"
workstream: "0037"
kind: task
depends_on:
  - credential-exposure-suite
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/ResourceExhaustionScenario.java
  - interop/scenarios/resource-exhaustion.toml
status: done
merged_as: ""
---
# Resource Exhaustion Suite

An author instance that has stopped serving is not distinguishable, from outside, from one that is gone. Every bound in this repository exists so that a caller cannot produce that state, and this is where each one is pushed rather than assumed.

**Steps:**

1. Enumerate the exhaustible resources: request threads, the concurrent stream budget, the store's capacity counters, the job queue, the artifact store, the subscription ledger, and the operation store.
2. For each, drive it past its bound from an authorized caller and assert the refusal is the declared one, that the author still answers every other route, and that the bound is the contract's rather than an incidental limit.
3. Assert the stream budget in particular protects the request threads: saturating the stream bound is proved to leave request threads available, which is the property the asynchronous route exists for.
4. Assert recovery: after each exhaustion ends, the resource returns to its prior state exactly, with counters equal to contents.
5. Assert one caller cannot exhaust a shared resource for everybody, using the per-caller bound each of those resources declares in the contract, and that a second caller is served throughout. Every enumerated resource has one: a bound that is only a total is a bound one client can spend on everybody else's behalf.

**Tests:**

- Each resource refuses at exactly its contract bound with its declared category, and the author answers every other route throughout.
- Saturating the stream bound leaves request threads available, proved by serving other routes at full stream saturation.
- Each resource returns to its prior state exactly after the exhaustion ends, with counters equal to contents.
- A second caller is served throughout every single-caller exhaustion, proving the per-caller bounds hold, and every enumerated resource is asserted to declare one so none is exhaustible by a single caller.
- No exhaustion produces an unhandled failure, an exhausted thread pool, or an instance that stops answering, asserted after each.

- **Done when:** `./mvnw verify -pl interop -Dtest=ResourceExhaustionScenario` proves each enumerated resource refusing at its contract bound with its declared category while every other route still answers, request threads available under full stream saturation, exact recovery with counters equal to contents, a second caller served throughout every single-caller exhaustion with a per-caller bound declared for every enumerated resource, and no unhandled failure or unresponsive instance.
