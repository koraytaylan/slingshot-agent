---
id: durable-key-ring
title: "Durable Continuation Key Ring"
workstream: "0009"
kind: task
depends_on:
  - event-store-generation
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/RepositoryKeyAuthority.java
  - core/src/main/java/rs/slingshot/agent/store/RotationLease.java
  - core/src/test/java/rs/slingshot/agent/store/RepositoryKeyAuthorityTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/KeyRotationScenario.java
  - interop/scenarios/key-rotation.toml
status: done
merged_as: ""
---
# Durable Continuation Key Ring

Plan 0002 said every deployment implements all of the authority and none may implement it more cheaply. This is where that promise either holds or quietly stops holding, because a repository-backed ring is where somebody would be tempted to notice there is only one node today.

**Steps:**

1. Author fixtures for a ring created once under concurrency, a rotation held by one holder while another attempts it, a rotation attempted before the prior key's retention ends, and a read by a principal that is not the service user.
2. Implement `RepositoryKeyAuthority` against Plan 0002's contract, storing the ring at its declared path and using compare-and-set for every write, with no branch on node count, clustering, or deployment.
3. Implement `RotationLease` as a claim held for the contract's rotation-lease duration, so two nodes deciding to rotate at once produce one rotation and one refusal rather than two keys.
4. Keep the ring readable and writable by the service user alone, enforced by the access-control entries the initialisation script creates, and prove a request-user session cannot read it.
5. Generate key material from the platform's cryptographically secure source, never from a seeded or time-derived one, and refuse to start the authority if that source is unavailable rather than falling back.

**Tests:**

- Concurrent creation produces exactly one ring, and concurrent rotation produces exactly one rotation and one lease refusal, both driven from the harness's two nodes against one shared repository.
- A rotation before the prior key's retention ends is refused naming the retention instant; after it, the rotation succeeds and the prior key is retained.
- Every write is proved to go through compare-and-set, asserted over the type, and no path branches on node count or deployment.
- A session adapted from a request user is refused a read of the ring, on a running instance.
- A token issued under the prior key validates and reports the prior key until its retention ends, then is refused.
- An unavailable secure source refuses startup rather than producing key material.

- **Done when:** `./mvnw verify -pl core -Dtest=RepositoryKeyAuthorityTest && ./mvnw verify -pl interop -Dtest=KeyRotationScenario` proves single creation and single rotation under concurrency, a refused early rotation naming its retention instant, compare-and-set on every write with no deployment branch, a request-user read refused on a running instance, prior-key validation through retention, and refusal to start without a secure source.
