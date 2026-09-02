---
id: continuation-state-and-token
title: "Continuation State and Token"
workstream: "0008"
kind: task
depends_on:
  - operation-identity
gated: false
touches:
  - schemas/agent-protocol/continuation/state.json
  - schemas/agent-protocol/continuation/token.json
  - core/src/main/java/rs/slingshot/agent/continuation/ContinuationState.java
  - core/src/main/java/rs/slingshot/agent/continuation/ContinuationToken.java
  - core/src/main/java/rs/slingshot/agent/continuation/QueryDigest.java
  - core/src/main/java/rs/slingshot/agent/continuation/package-info.java
  - core/src/test/java/rs/slingshot/agent/continuation/ContinuationTokenTest.java
  - "core/src/test/resources/fixtures/continuation-token/**"
status: done
merged_as: ""
---
# Continuation State and Token

The query a token belongs to is part of what is signed, so a token cannot be carried from one query to another. That is a real attack rather than a hypothetical one: a position in one result set is a perfectly plausible position in another, and a token that only said "position 400" would happily resume the wrong search.

**Steps:**

1. Author fixtures for accepted state, for state whose query digest belongs to another query, for state whose target digest belongs to another target, for an expired token, and for a state exactly at and one past the key-state byte bound.
2. Implement `ContinuationState` holding the generation, the target identity digest, the query digest, the position, and the expiry, all five required.
3. Implement `QueryDigest` over the canonical bytes of the whole query — every argument that changes which rows are returned or their order — so two queries differing in any of them produce different digests.
4. Implement `ContinuationToken` as the state plus an integrity value, with the state's encoded size bounded at the contract's key-state limit universally: there is no single-node, private, or node-local exception that would let one deployment carry a different amount of state than another.
5. Make each refusal its own case — malformed, integrity invalid, wrong target, wrong query, expired — matching the categories the client's registry already declares, so a failure crosses the wire as the category the caller expects.

**Tests:**

- Accepted state constructs; each absent member is refused distinctly.
- A token whose query digest is another query's is refused as wrong-query, and one whose target digest is another target's as wrong-target, distinctly.
- The five refusal cases are asserted to be exactly the categories the client declares, in both directions.
- The encoded state is accepted at exactly the key-state bound and refused one past it, and the bound is proved to be read from the contract with no deployment-conditional path.
- The query digest is proved sensitive to every argument that affects the result set or its order, one argument at a time.

- **Done when:** `./mvnw verify -pl core -Dtest=ContinuationTokenTest` proves five required members, distinct wrong-query and wrong-target refusals, a refusal set equal to the client's declared categories in both directions, both sides of the universal key-state bound with no deployment-conditional path, and per-argument query-digest sensitivity.
