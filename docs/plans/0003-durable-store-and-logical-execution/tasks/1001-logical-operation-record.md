---
id: logical-operation-record
title: "Logical Operation Record"
workstream: "0010"
kind: task
depends_on:
  - capacity-accounting
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/execution/LogicalOperation.java
  - core/src/main/java/rs/slingshot/agent/execution/OperationState.java
  - core/src/main/java/rs/slingshot/agent/execution/OperationStore.java
  - core/src/main/java/rs/slingshot/agent/execution/package-info.java
  - core/src/test/java/rs/slingshot/agent/execution/LogicalOperationTest.java
  - "core/src/test/resources/fixtures/logical-operation/**"
status: done
merged_as: ""
---
# Logical Operation Record

One durable thing per operation identifier and generation, claimed by creation, so that the acceptance and the record are the same act. Accepting first and recording afterwards leaves a window in which the client has been told yes and this side has forgotten.

What the record holds is decided here and nowhere else, because every later plan that needs a fact about an operation — which caller submitted it, which target it was against, which revision of their environment it named — can only read one that this task wrote down.

**Steps:**

1. Author fixtures for a record at each state, for every legal transition, for every illegal one, for a record whose request-start instant is absent, and for request-start instants at and one past the contract's allowance on either side of this side's own clock.
2. Implement `OperationState` as the closed set the protocol's kinds imply, with terminality intrinsic and every legal transition declared as data rather than as a chain of conditions.
3. Implement `LogicalOperation` holding the submission digest, the command contract identity, the whole operation identity including its target identity digest and environment revision, the submitting caller, the request-start instant, the state, and the attempt count, with every member required at creation. The target digest and the environment revision are held because the identifier alone does not determine them and a later resend has to be comparable against what was actually submitted; the caller is held because the read routes and the console decide ownership against it.
4. Implement `OperationStore` to create a record by claim at its derived path, and to advance state only by compare-and-set from the exact state the caller read.
5. Anchor every relative retention the record carries at the request-start instant recorded here, not at any later moment, because that is where the client anchors it and a later anchor silently lengthens a window the client is budgeting against.
6. Refuse a request-start instant further from this side's own clock than the contract's declared allowance, in either direction, naming both instants — an instant in the past is a record swept before its client can read it, and one in the future is capacity nothing will ever release, and neither is something a client should be able to choose.

**Tests:**

- Every declared transition is accepted from its exact predecessor state and refused from every other, one pair at a time across the whole matrix.
- A terminal state accepts no further transition, and a repeat of the same terminal transition is accepted as idempotent without changing the record.
- A record is created exactly once under concurrent creation, and the second caller reads the first's record.
- An advance from a state the caller did not read is refused without writing, proved by reading back.
- Retention derived from the record is asserted to be measured from the request-start instant, proved by a record whose creation and request-start differ.
- The request-start allowance is proved at exactly the contract's value and one past it in both directions, with the refusal naming both instants and no record written.
- Every member is asserted required at creation, and the target digest, the environment revision, and the caller are each proved readable back from a stored record.

- **Done when:** `./mvnw verify -pl core -Dtest=LogicalOperationTest` proves the complete transition matrix in both directions, terminal finality with an idempotent repeat, exactly-once creation under concurrency, a non-writing stale advance, retention anchored at request-start rather than at record creation, both sides of the request-start clock allowance with nothing written, and a record that holds and returns the target digest, the environment revision, and the submitting caller.
