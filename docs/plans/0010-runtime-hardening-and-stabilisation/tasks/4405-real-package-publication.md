---
id: real-package-publication
title: "Build and Publish Actual Content Package Bytes"
workstream: "0044"
kind: task
depends_on:
  - bounded-repository-traversal
  - atomic-intake-publication
gated: false
touches:
  - core/src/main/java/rs/slingshot/agent/command/content/DownloadContentPackageHandler.java
  - core/src/main/java/rs/slingshot/agent/command/StagingArea.java
  - core/src/main/java/rs/slingshot/agent/store/ArtifactStore.java
  - aem/src/main/java/rs/slingshot/agent/aem
  - core/src/test/java/rs/slingshot/agent/command/content
  - interop/src/test/java/rs/slingshot/agent/interop/tier/DownloadContentPackageScenario.java
status: pending
merged_as: ""
---
# Build and Publish Actual Content Package Bytes

Finding(s): R15 in [FINDINGS.md](../FINDINGS.md).

**Steps:**

1. Replace the manifest-only fabricated publication with a supported package build over caller-visible selected content.
2. Validate and durably publish the complete bounded archive before reporting its actual count/digest/slot, then close staging.
3. Open the artifact after staging closes and verify archive structure, filter, selected content and installed HTTP download against its recorded digest.

- **Done when:** A successful package command returns a durable downloadable valid package containing the selected content, with count/digest matching the complete archive rather than filter.xml.
