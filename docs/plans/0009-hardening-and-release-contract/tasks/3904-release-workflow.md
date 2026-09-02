---
id: release-workflow
title: "Release Workflow"
workstream: "0039"
kind: task
depends_on:
  - release-artifact-contract
gated: false
touches:
  - .github/workflows/release.yml
  - development/src/test/java/rs/slingshot/agent/development/ReleaseWorkflowTest.java
  - support/release-attestation-policy.toml
status: done
merged_as: ""
---
# Release Workflow

"Built here" is the last part of the claim, and the only part a repository cannot check about itself. What it can do is arrange for provenance to be produced by exactly one job holding exactly one extra permission, and refuse every arrangement where more than one could.

**Steps:**

1. Author fixtures for two jobs holding the attestation permission, an attestation job with write access to content, an unpinned action in the release workflow, and an accepted arrangement.
2. Write `.github/workflows/release.yml` building through `scripts/build_release_artifacts`, verifying through `scripts/verify_release_artifacts`, producing build provenance for every artifact, and publishing to both declared targets from one built set rather than building once per target.
3. Declare in `support/release-attestation-policy.toml` which job may hold the attestation permission, and refuse a workflow where any other job does or where that job also holds content write access.
4. Hold the release workflow to the same policy the other two are held to, with no exception for being a release.
5. Publish the same bytes to both targets and prove it: the digest of every asset attached to the repository release is asserted equal to the digest of the corresponding artifact sent to the Maven repository, so the two can never diverge unnoticed.
6. Refuse the workflow if a target's preconditions are unmet, per target, so a release run that could not publish to the Maven repository fails before building rather than producing artifacts nobody may distribute — while a release asset stays publishable on its own terms.

**Tests:**

- Exactly one job holds the attestation permission; two jobs holding it and that job also holding content write access are two distinct findings.
- The release workflow passes the same policy check as the other two, with no exemption reachable.
- The workflow builds and verifies through the two scripts and does neither inline.
- Provenance is produced for every artifact in the release artifact inventory, in both directions.
- Each target's preconditions are checked before any build step runs, and an unmet Maven precondition fails the run while leaving the release-asset target's own check independent.
- Every artifact published to both targets is asserted byte-identical between them by digest, and a fixture divergence is detected naming the artifact.

- **Done when:** `./mvnw verify -pl development -Dtest=ReleaseWorkflowTest` proves exactly one attestation-holding job with two distinct violations detected, the release workflow held to the shared policy with no exemption, building and verifying only through the two scripts, provenance for every inventoried artifact in both directions, one built set published to both targets with byte-identical digests and a fixture divergence detected, and per-target precondition checks that fail before any build step.
