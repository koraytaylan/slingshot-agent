---
id: result-bounds-and-overflow
title: "Result Bounds and Overflow"
workstream: "0017"
kind: task
depends_on:
  - paged-query-and-continuation
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/ResultAssembly.java
  - core/src/main/java/rs/slingshot/agent/command/OverflowPublication.java
  - core/src/test/java/rs/slingshot/agent/command/ResultAssemblyTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ResultOverflowScenario.java
  - policy/design-patterns.toml
  - interop/scenarios/result-overflow.toml
status: done
merged_as: ""
---
# Result Bounds and Overflow

Nothing is truncated. A truncated answer is not a smaller answer but an unparseable one, and the client is built to fetch rather than to cope.

**Steps:**

1. Author fixtures for a result at and one past each distinct registry bound, a result that overflows during assembly rather than at the end, and an overflow whose artifact publication fails.
2. Implement `ResultAssembly` to measure against the row's bound as the result is built, so an overflow is known before the whole result is held.
3. Implement `OverflowPublication` to write the artifact through Plan 0003's store, with its byte count and digest, and to answer with a reference rather than with bytes.
4. Reserve artifact capacity before assembly begins for a command whose manifest says it may overflow, so a command does not run to completion and then discover there is nowhere to put its answer.
5. Make a failed publication a declared failure category with no partial answer: neither a truncated inline result nor a reference to an artifact that does not exist.

**Tests:**

- Each distinct registry bound is proved at exactly the limit inline and one byte past it as an artifact reference.
- An overflow detected during assembly is proved not to have held the whole result, asserted structurally.
- Capacity is reserved before assembly for an overflow-capable manifest, proved by a store below the reservation refusing before the handler runs.
- A failed publication produces its declared category, with neither a truncated result nor a dangling reference reachable afterwards.
- On a running instance, an overflowing read is fetched through the artifact route and verified against the reported digest and count.

- **Done when:** `./mvnw verify -pl core -Dtest=ResultAssemblyTest && ./mvnw verify -pl interop -Dtest=ResultOverflowScenario` proves both sides of every distinct bound with overflow becoming a reference, assembly-time detection without holding the result, reservation before the handler runs, a failed publication with no partial answer, and an end-to-end verified artifact fetch.
