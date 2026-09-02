---
id: console-artifact-download
title: "Console Artifact Download"
workstream: "0032"
kind: task
depends_on:
  - maintenance-console
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/ArtifactLink.java
  - aem/src/test/java/rs/slingshot/agent/aem/console/ArtifactLinkTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/ConsoleArtifactScenario.java
  - interop/scenarios/console-artifact.toml
status: done
merged_as: ""
---
# Console Artifact Download

An operator looking at an operation that produced an answer too large to display needs to be able to fetch it. Doing that through the existing artifact route rather than a console-specific one is what keeps the byte count, the digest, and the deadlines identical wherever the artifact came from.

**Steps:**

1. Author fixtures for an operation with one artifact, with several slots, with none, and one whose artifact has been swept.
2. Implement `ArtifactLink` producing a link to the existing artifact route addressed by operation and slot, and never to a repository path.
3. Show each artifact's slot, byte count, and digest beside the link, so a downloader can verify what they received without trusting the page.
4. Show a swept artifact as expired with the instant it expired, rather than as a link that will fail.
5. Refuse to render a link for a viewer the artifact route would refuse, so the page never offers a download that will be denied.

**Tests:**

- The rendered link addresses the artifact route by operation and slot, and no repository path appears anywhere in the page.
- The shown byte count and digest match what the route serves, proved by fetching and comparing on a running instance.
- A swept artifact is shown as expired with its instant rather than as a link.
- A viewer the route would refuse is shown no link, proved for a non-member and for another caller's operation.
- Several slots are each linked separately and each verifies independently.

- **Done when:** `./mvnw verify -pl aem -Dtest=ArtifactLinkTest && ./mvnw verify -pl interop -Dtest=ConsoleArtifactScenario` proves links addressing the existing route with no repository path anywhere, a shown count and digest matching what the route serves on fetch, an expired artifact shown rather than linked, no link offered where the route would refuse, and independent verification of several slots.
