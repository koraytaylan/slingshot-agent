---
id: token-and-stream-fuzzing
title: "Token and Stream Fuzzing"
workstream: "0035"
kind: task
depends_on:
  - parser-fuzzing
gated: false
touches:
  - development/src/main/java/rs/slingshot/agent/development/fuzz/ContinuationTokenTarget.java
  - development/src/main/java/rs/slingshot/agent/development/fuzz/EventEncoderTarget.java
  - development/src/main/java/rs/slingshot/agent/development/fuzz/ResumptionIdentifierTarget.java
  - "fuzz/corpus/continuation-token/**"
  - "fuzz/corpus/event-encoder/**"
  - "fuzz/corpus/resumption-identifier/**"
  - development/src/test/java/rs/slingshot/agent/development/fuzz/TokenStreamFuzzTest.java
status: done
merged_as: ""
---
# Token and Stream Fuzzing

A continuation token and a resumption identifier are the two values a caller supplies that decide where this side reads from. A forged one that validated would be a caller reading somebody else's results, so the property here is stronger than well-formedness.

**Steps:**

1. Seed the token corpus from valid tokens issued by the authority, and mutate from there, so the fuzzer explores near-valid rather than obviously invalid inputs.
2. Implement the continuation-token target with two properties: no input validates unless it was issued under a key the authority holds, and every refusal is one of the six declared categories with no seventh outcome.
3. Implement the resumption-identifier target with the property that no input reaches an event belonging to another operation, another subscription, or another generation.
4. Implement the event-encoder target with the property that every encoded event decodes back to the same event, and no input produces output crossing a bound.
5. Include a forgery corpus built by mutating the integrity value and by re-signing under a key the authority does not hold, and assert none validates.

**Tests:**

- No mutated or forged token validates, over the whole corpus and a declared iteration count.
- Every token refusal is one of the six declared categories, with no seventh reachable.
- No resumption identifier reaches another operation's, subscription's, or generation's events.
- Every encoded event decodes to the same event, and no input produces over-bound output.
- The forgery corpus is exhaustive over the mutation kinds declared, and a fixture forgery that validates fails the suite.

- **Done when:** `scripts/run_fuzz_target continuation-token && scripts/run_fuzz_target resumption-identifier && scripts/run_fuzz_target event-encoder && ./mvnw verify -pl development -Dtest=TokenStreamFuzzTest` proves no forged token validates across the declared mutation kinds, exactly six refusal categories with no seventh, no cross-operation or cross-generation reachability from any resumption identifier, and encoder round-tripping with no over-bound output.
