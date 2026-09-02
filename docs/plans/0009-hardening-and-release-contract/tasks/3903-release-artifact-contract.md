---
id: release-artifact-contract
title: "Release Artifact Contract"
workstream: "0039"
kind: task
depends_on:
  - interop-workflow
gated: false
touches:
  - pom.xml
  - all/pom.xml
  - scripts/build_release_artifacts
  - scripts/verify_release_artifacts
  - support/release-artifacts.toml
  - development/src/main/java/rs/slingshot/agent/development/ReleaseArtifacts.java
  - development/src/test/java/rs/slingshot/agent/development/ReleaseArtifactsTest.java
status: done
merged_as: ""
---
# Release Artifact Contract

"These bytes, from this source" is a claim, and the only way to check it is to build twice and compare. A package whose product dependencies are all provided also lets it publish a components list that is nearly empty, which is a claim worth being able to verify rather than merely stating.

**Steps:**

1. Author fixtures for an archive with variable timestamps, one with unstable entry order, one carrying an environment-dependent value, and an accepted arrangement.
2. Set the reproducible build timestamp from the source rather than from the clock, fix archive entry order, and remove every environment-dependent value from every produced archive.
3. Write `scripts/build_release_artifacts` producing the container package, both bundles, their sources and documentation archives, and a components list, recording every artifact's digest in `support/release-artifacts.toml`.
4. Write `scripts/verify_release_artifacts` building a second time from the same source and comparing byte for byte, reporting the first differing entry rather than only that they differ.
5. Publish the components list beside the archives and check it: an entry the archives do not contain and an archive entry with no components row are two findings.

**Tests:**

- Two builds from one source produce byte-identical archives, and a fixture with a variable timestamp, unstable order, or environment-dependent value fails naming the first differing entry.
- Every produced artifact has a digest row and every row names a produced artifact, sources and documentation archives included.
- The sources and documentation archives are asserted deterministic on the same terms as the rest, since an archive that varies between builds would break the digest equality the two distribution targets are held to.
- The components list is asserted equal to what the archives actually contain, in both directions.
- The components list for the bundles is asserted to name nothing from another party, which is the claim the whole dependency policy has been building toward.
- Verification is proved to build rather than to compare recorded digests, by a fixture whose recorded digest matches and whose bytes differ.

- **Done when:** `scripts/build_release_artifacts && scripts/verify_release_artifacts && ./mvnw verify -pl development -Dtest=ReleaseArtifactsTest` proves byte-identical archives across two builds with the first differing entry named on failure, two-way artifact-to-digest correspondence, a components list equal to the archives' contents naming nothing from another party, and verification that rebuilds rather than trusting a recorded digest.
