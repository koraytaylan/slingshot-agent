---
id: retention-policy
title: "Retention Policy"
workstream: "0012"
kind: task
depends_on:
  - terminal-commit
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/store/RetentionPolicy.java
  - core/src/main/java/rs/slingshot/agent/store/RetainedUntil.java
  - core/src/test/java/rs/slingshot/agent/store/RetentionPolicyTest.java
  - "core/src/test/resources/fixtures/retention/**"
status: done
merged_as: ""
---
# Retention Policy

The client budgets against a window it was told about, and it anchors that window at the moment it made the request. Anchoring it anywhere later here silently lengthens it, which sounds generous and means the client's arithmetic is wrong.

**Steps:**

1. Author fixtures for each retained kind at exactly its minimum, one below it, at the persisted maximum, and one past it, plus a record whose creation and request-start differ.
2. Implement `RetentionPolicy` deriving a retained-until instant for each kind — result, snapshot, operation detail, artifact — from the contract's minimum for that kind and the record's request-start instant.
3. Refuse a configured retention below the declared minimum for its kind, naming both, so a deployment cannot shorten a window the client relies on.
4. Cap an advertised relative retention at the contract's persisted maximum, so a value that cannot be honoured is never advertised.
5. Make `RetainedUntil` a value that can only be produced from a record, never from a bare instant, so nothing computes a retention from a clock reading alone.

**Tests:**

- Each kind's retention is accepted at exactly its minimum and refused one below, naming the kind and both values.
- The advertised relative retention is capped at exactly the persisted maximum and a larger configured value is refused rather than silently clamped.
- Retention is asserted measured from request-start, by a record whose creation is later and whose retained-until is unchanged.
- No retention can be produced from a bare instant, asserted over the type's surface.
- The minimum for every retained kind is read from the contract, with none declared in this module, proved by the source policy.

- **Done when:** `./mvnw verify -pl core -Dtest=RetentionPolicyTest` proves both sides of every kind's minimum, a refused rather than clamped over-maximum advertisement, retention anchored at request-start under a later creation, no retention derivable from a bare instant, and no minimum declared outside the contract accessor.
