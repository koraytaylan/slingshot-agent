---
id: protocol-conformance-vectors
title: "Protocol Conformance Vectors"
workstream: "0008"
kind: task
depends_on:
  - committed-schemas-and-digests
gated: false
touches:
  - schemas/agent-protocol-vectors.json
  - schemas/agent-protocol-vector-inventory.toml
  - core/src/test/java/rs/slingshot/agent/wire/ProtocolVectorTest.java
  - development/src/main/java/rs/slingshot/agent/development/VectorInventory.java
  - development/src/test/java/rs/slingshot/agent/development/VectorInventoryTest.java
  - "development/src/test/resources/fixtures/vector-inventory/**"
status: done
merged_as: ""
---
# Protocol Conformance Vectors

A vector is what makes a disagreement between two implementations a failing test in whichever one changed, rather than a refused submission in production two weeks later. That only works if a document kind cannot exist without one.

**Steps:**

1. Author the inventory fixtures first: a document kind with no vector, a vector naming a kind that does not exist, a vector with no note saying what it proves, and a duplicate vector identifier.
2. Write `schemas/agent-protocol-vectors.json` with one entry per vector: the document kind, the input, the exact expected canonical bytes, and a note saying what it proves.
3. Cover every document kind in both directions — a document this side writes and one it reads — and for each bound, one vector at the limit and one past it.
4. Implement the inventory check comparing the vector set against the document kinds the typed models declare, so a kind with no vector fails and a vector for no kind fails.
5. Carry the client's own vectors for every kind both sides produce, unchanged, and prove this side against them beside the local ones.

**Tests:**

- Every vector's expected bytes are produced exactly, and a fixture vector differing by one character fails naming the vector.
- Every document kind has at least one accepted and one refused vector, and a kind with neither fails naming it.
- A vector naming an unknown kind, one with no note, and a duplicate identifier are three distinct rejections.
- The carried client vectors pass unchanged, and a modified copy fails, proving they are the client's bytes rather than regenerated ones.
- Every bound named in any typed model has a vector at the limit and one past it, asserted by comparing the bound set against the vector notes' declared bounds.

- **Done when:** `./mvnw verify -pl core -Dtest=ProtocolVectorTest && ./mvnw verify -pl development -Dtest=VectorInventoryTest` proves byte-exact output for every vector, at least one accepted and one refused vector per document kind, three distinct inventory rejections, unchanged client vectors passing, and a vector at and past every bound any typed model declares.
