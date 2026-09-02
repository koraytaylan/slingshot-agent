---
id: canonical-contract-authentication
title: "Canonical Contract Authentication Order"
workstream: "0005"
kind: task
depends_on:
  - canonical-byte-writer
  - digest-primitives
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/json/CanonicalContractAuthority.java
  - core/src/main/java/rs/slingshot/agent/json/AuthenticationStep.java
  - core/src/test/java/rs/slingshot/agent/json/CanonicalContractAuthorityTest.java
  - "core/src/test/resources/fixtures/canonical-authentication/**"
status: done
merged_as: ""
---
# Canonical Contract Authentication Order

Contract drift and annotation drift are two different failures with two different causes, and neither may hide inside the other. Keeping them apart is entirely a matter of what is checked before what.

**Steps:**

1. Author one fixture per reversal: the contract bytes altered, the annotation naming another contract, the role schema's own bytes altered, and an arrangement where two of those are wrong at once.
2. Implement `CanonicalContractAuthority` to run exactly four steps in order — authenticate the committed contract bytes against their committed digest, check a role schema's annotation against that digest, believe the role schema's own digest, and only then permit an identity to be assembled from it.
3. Give each step its own `AuthenticationStep` failure naming what it compared and what it found, so a caller can say which of the four failed without inspecting anything else.
4. Make the order unskippable in structure rather than by convention: a later step's input is a value only an earlier step can produce, so there is no call sequence that reaches step three without step one.
5. Report the first failure and stop, and prove that a document failing two steps reports the earlier one, because a caller told about the later failure would fix the wrong thing.

**Tests:**

- Each of the four alterations fails at its own step, named, and none is reported as another step's failure.
- A fixture failing two steps reports the earlier one and does not run the later.
- The ordering is proved structurally: no public call sequence reaches a later step without the earlier step's produced value, asserted over the module's own types.
- A correct arrangement produces a value that is accepted, and that value is proved to be the only route to an identity assembly.
- Every failure names the digest it compared and the digest it found, without disclosing the committed bytes.

- **Done when:** `./mvnw verify -pl core -Dtest=CanonicalContractAuthorityTest` proves four distinct step failures, first-failure-wins on a doubly-broken input, a structurally unskippable order with no bypassing call sequence, a single accepted route to identity assembly, and failures that name digests without disclosing contract bytes.
