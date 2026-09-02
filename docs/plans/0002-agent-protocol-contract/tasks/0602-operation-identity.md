---
id: operation-identity
title: "Operation Identity"
workstream: "0006"
kind: task
depends_on:
  - command-contract-identity
gated: false
touches:
  - schemas/agent-protocol/identity/operation.json
  - core/src/main/java/rs/slingshot/agent/identity/OperationIdentity.java
  - core/src/main/java/rs/slingshot/agent/identity/EventStoreGeneration.java
  - core/src/main/java/rs/slingshot/agent/identity/AgentOperationIdentifier.java
  - core/src/test/java/rs/slingshot/agent/identity/OperationIdentityTest.java
  - "core/src/test/resources/fixtures/operation-identity/**"
status: done
merged_as: ""
---
# Operation Identity

Which operation, at which incarnation of the store, against which target, at which revision. Four values that together mean a durable thing, and any one of them changing means a different durable thing.

**Steps:**

1. Author fixtures for a complete identity, for each member absent, for a generation of zero and one, for an operation identifier of the wrong shape, and for an environment revision at and one past its bound.
2. Implement `EventStoreGeneration` as a value that starts at one and never decreases, refusing zero and refusing a value below one already seen, so a store that was rebuilt cannot present itself as an earlier incarnation.
3. Implement `AgentOperationIdentifier` constrained to exactly sixty-four lowercase hexadecimal characters and to the identifier byte bound the contract declares, whichever is stricter, and refuse anything else at construction.
4. Implement `OperationIdentity` as the four members together, with the target identity digest and the environment revision opaque here — this side compares them and never parses them, because their meaning belongs to the client's configuration.
5. Commit the schema and prove the typed model and the schema describe the same members in both directions.

**Tests:**

- A complete identity constructs; each absent member is refused distinctly.
- A generation of zero is refused, one is accepted, and a decrease below a generation already observed is refused naming both.
- The operation identifier is refused for uppercase, short, long, and non-hexadecimal input, and accepted at exactly its shape.
- The environment revision is accepted at exactly its bound and refused one past it; the target digest is refused for every wrong shape.
- The committed schema's member set and the typed model's are asserted equal in both directions, so a member added to either fails.

- **Done when:** `./mvnw verify -pl core -Dtest=OperationIdentityTest` proves complete construction, four distinct absence refusals, generation monotonicity including a refused decrease, four identifier-shape refusals, both sides of the revision bound, and two-way schema-to-model correspondence.
