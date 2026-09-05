---
id: fenced-continuation-authority
title: "Fence Continuation Key Writes with Persisted Ownership"
workstream: "0041"
kind: task
depends_on:
  - atomic-execution-fences
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/DefaultContinuationKeyAuthority.java
  - core/src/main/java/rs/slingshot/agent/store/RotationLease.java
  - core/src/main/java/rs/slingshot/agent/continuation
  - core/src/test/java/rs/slingshot/agent/store/DefaultContinuationKeyAuthorityTest.java
status: pending
merged_as: ""
---
# Fence Continuation Key Writes with Persisted Ownership

Finding(s): R10 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Reproduce a fabricated unexpired Lease succeeding while another owner holds the persisted lease.
2. Validate persisted holder, expiry and acquisition epoch within the same conflict-protected key write and enforce the legal retention transition.
3. Test fabricated, expired, stale, renewed and reacquired leases with tokens issued before and after rotation.

- **Done when:** Only the current persisted lease epoch can change the key ring, and valid pre-rotation tokens remain verifiable for the required retention interval.
