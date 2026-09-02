---
id: release-acceptance-matrix
title: "Release Acceptance Matrix"
workstream: "0040"
kind: task
depends_on:
  - live-author-verification
gated: false
touches:
  - support/acceptance-matrix.toml
  - scripts/release_acceptance
  - development/src/main/java/rs/slingshot/agent/development/AcceptanceMatrix.java
  - development/src/test/java/rs/slingshot/agent/development/AcceptanceMatrixTest.java
status: done
merged_as: ""
---
# Release Acceptance Matrix

A row does not become supported because the code compiled. The matrix binds every deployment row to the evidence that actually ran against it, and a row with none appears as declared and unproved — which is a useful thing to publish rather than an embarrassing one.

**Steps:**

1. Author fixtures for a row with complete evidence, a row with none, a row whose evidence came from a different row, and evidence naming a scenario that does not exist.
2. Write `support/acceptance-matrix.toml` with one entry per deployment row naming which tier ran, which scenarios, on which instance, and when — with the evidence recorded by the run rather than written by hand — and, per row, the properties that are the deployment's rather than this build's: how the artifact arrives, whether the row's own ingress has been observed passing an unbuffered event stream, and whether a clustered arrangement was exercised. A row whose streaming has never been watched arrive is streaming that is declared and unproved, which is a more useful thing to publish than a claim nobody tested.
3. Implement `AcceptanceMatrix` refusing evidence that names a scenario, tier, or row that does not exist, and refusing evidence recorded against one row from a run on another.
4. Write `scripts/release_acceptance` running every gate a release requires, in order, recording each result into the matrix, and refusing to record a result it did not observe.
5. Render every row's status as proved or declared-and-unproved, and make a release naming a row as supported without evidence a refusal.

**Tests:**

- A row with complete evidence renders as proved and one with none as declared and unproved, and neither can be written by hand.
- A row claiming streaming support with no observation of its own ingress behind it is rendered as declared and unproved rather than as supported, and a release naming it as supported is refused.
- Evidence naming a nonexistent scenario, tier, or row is refused distinctly.
- Evidence recorded against a different row than the run occurred on is refused naming both.
- A release claiming a row as supported without evidence is refused naming the row.
- The matrix's row set equals the deployment matrix's, in both directions.

- **Done when:** `scripts/release_acceptance && ./mvnw verify -pl development -Dtest=AcceptanceMatrixTest` proves proved and declared-and-unproved rendered from recorded rather than hand-written evidence, an arrival path and an ingress-streaming observation recorded per row with an unobserved one rendered unproved, three distinct nonexistent-reference refusals, cross-row evidence refused naming both, a release refused for an unproved supported claim, and two-way correspondence with the deployment matrix.
