---
id: dependency-advisory-pin
title: "Dependency Advisory Pin"
workstream: "0038"
kind: task
depends_on:
  - resource-exhaustion-suite
gated: false
touches:
  - compatibility/advisory-database.toml
  - scripts/checkout_pinned_advisory_database
  - scripts/record_advisory_owner_review
  - scripts/quality
  - policy/quality-gate.toml
  - development/src/main/java/rs/slingshot/agent/development/AdvisoryGate.java
  - development/src/test/java/rs/slingshot/agent/development/AdvisoryGateTest.java
status: done
merged_as: ""
---
# Dependency Advisory Pin

There is deliberately no timestamp and no freshness claim. A snapshot's author chooses those values, so neither of them authenticates anything, and a gate that reported freshness would be reporting something it cannot check.

**Steps:**

1. Author fixtures for a snapshot whose content digest does not match, one whose commit does not match, an absent snapshot, and an owner review that does not name the snapshot it reviewed.
2. Pin the advisory snapshot in `compatibility/advisory-database.toml` by origin, full commit, and content digest, with no timestamp field the file can carry.
3. Write `scripts/checkout_pinned_advisory_database` as a command that reaches the network, says so when it runs, and produces exactly the pinned snapshot or fails.
4. Implement `AdvisoryGate` authenticating the snapshot offline before checking anything against it, and checking every declared artifact — build-time and test-scope included, since those are code that runs.
5. Write `scripts/record_advisory_owner_review` binding an owner's review to the exact snapshot digest, and refuse a release whose snapshot has no matching review.

**Tests:**

- A content-digest mismatch, a commit mismatch, and an absent snapshot are three distinct refusals, none of which checks anything.
- The gate is proved to fetch nothing, by running it with no reachable network and with an ambient copy of a different snapshot present.
- Every declared artifact including build-time and test-scope entries is checked, and an artifact with no check fails.
- The file cannot carry a timestamp, and a fixture adding one is rejected as an unknown key.
- A release whose snapshot has no matching owner review is refused naming the snapshot.

- **Done when:** `scripts/quality && ./mvnw verify -pl development -Dtest=AdvisoryGateTest` proves three distinct authentication refusals with nothing checked, an offline gate that ignores an ambient different snapshot, coverage of every declared artifact including build-time and test-scope, a file that cannot carry a timestamp, and a release refused without a matching owner review.
