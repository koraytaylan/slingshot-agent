---
id: continuation-key-authority-contract
title: "Continuation Key Authority Contract"
workstream: "0008"
kind: task
depends_on:
  - continuation-state-and-token
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/continuation/ContinuationKeyAuthority.java
  - core/src/main/java/rs/slingshot/agent/continuation/KeyRing.java
  - core/src/main/java/rs/slingshot/agent/continuation/ValidatingKey.java
  - core/src/main/java/rs/slingshot/agent/continuation/KeyRingRefusal.java
  - core/src/test/java/rs/slingshot/agent/continuation/ContinuationKeyAuthorityTest.java
  - "core/src/test/resources/fixtures/key-authority/**"
status: done
merged_as: ""
---
# Continuation Key Authority Contract

One authority every deployment provides, whatever it is running on. A single instance is not permitted a cheaper version, because the guarantees would then change the day somebody added a node — and the code depending on them would not know.

**Steps:**

1. Author fixtures for an absent ring, a ring at and past the record bound, a key at and past the key bound, a rotation while the prior key is still retained, and a compare-and-set against a value that has changed.
2. Implement `ContinuationKeyAuthority` as a small linearizable store: read a ring, write it only if it still holds what the caller expected, and only while the caller holds the lease. Nothing observes node count and nothing branches on deployment.
3. Implement `KeyRing` with a current key, an optional retained prior key, and the instant the prior key stops being accepted, all bounded by the contract's record and key limits.
4. Implement validation to try the current key and then the prior one, reporting which succeeded as `ValidatingKey`, because "valid under the prior key" is the signal that a rotation is in progress and a caller should expect a new token.
5. Refuse a rotation while the prior key is still retained, naming when it stops being accepted, because rotating then would strand every token issued under it.

**Tests:**

- An absent ring is a distinct refusal from an empty one, and neither is created implicitly.
- The record bound and the key bound are each proved at exactly the limit and one past it.
- A compare-and-set against a changed value fails without writing, proved by reading back the unchanged ring.
- Validation reports the current key for a token issued now and the prior key for one issued before the last rotation, and refuses one issued before the prior key's retention ended.
- A rotation while the prior key is retained is refused naming the retention instant; one after it succeeds, and the retention span is asserted to exceed the longest token lifetime plus the declared clock skew.
- No path in the type branches on deployment, node count, or clustering, asserted over the module.

- **Done when:** `./mvnw verify -pl core -Dtest=ContinuationKeyAuthorityTest` proves an absent ring distinct from an empty one, both sides of both bounds, a non-writing failed compare-and-set, current-then-prior validation with the key reported, a refused early rotation whose retention exceeds token lifetime plus skew, and no deployment-conditional path anywhere in the type.
