---
id: central-publication-prerequisites
title: "Central Publication Prerequisites"
workstream: "0039"
kind: task
depends_on:
  - software-bill-of-materials
gated: false
touches:
  - pom.xml
  - support/publication-metadata.toml
  - policy/central-prerequisites.toml
  - development/src/main/java/rs/slingshot/agent/development/CentralPrerequisites.java
  - development/src/test/java/rs/slingshot/agent/development/CentralPrerequisitesTest.java
  - "development/src/test/resources/fixtures/central-prerequisites/**"
status: done
merged_as: ""
---
# Central Publication Prerequisites

The central repository refuses a publication that is missing a signature, a sources archive, documentation, or any of six pieces of project metadata — and it refuses it at the end, after a release run has built everything and reached the network. Every one of those is decidable here, offline, in a second.

That is the same argument the content-package analyser task made: discovering a packaging defect from a deployment pipeline is discovering it in the most expensive place there is. This is the release-side version of it.

**Steps:**

1. Author fixtures for each prerequisite absent in turn: an unsigned artifact, a missing sources archive, missing documentation, each of the six required metadata elements, and a version that is still a snapshot.
2. Write `policy/central-prerequisites.toml` naming every prerequisite the registry enforces, so the list is data somebody can compare against the registry's own published requirements rather than lore in a script.
3. Complete the project metadata every published module needs — name, description, project address, licence, developer, and source-control coordinates — declared once in the aggregator and inherited, with the source-control coordinates derived from the declared repository rather than written twice.
4. Produce a sources archive and a documentation archive for every published module, with documentation generation held to warnings-as-errors like every other compilation, or record an explicit exemption for a packaging that cannot produce one — an exemption with a reason, never a silent absence.
5. Implement `CentralPrerequisites` checking every prerequisite against the built artifacts and the resolved model, reporting every failure at once rather than the first, and refusing a release that would be rejected remotely.

**Tests:**

- Each prerequisite absent in turn is a distinct finding naming it, and a complete arrangement passes.
- Every published artifact has a signature, and an unsigned artifact fails naming it; a signature that does not verify against the declared signing identity fails distinctly from an absent one.
- Every published module has a sources archive and a documentation archive, or an exemption carrying a reason; an exemption with none is rejected.
- Documentation generation is proved to run with warnings as errors, and a fixture module with a documentation warning fails.
- A snapshot version fails, the source-control coordinates are asserted derived from the declared repository rather than declared separately, and every failure is reported together rather than one at a time.

- **Done when:** `./mvnw verify -pl development -Dtest=CentralPrerequisitesTest` proves a distinct finding for every absent prerequisite with a complete arrangement passing, a verifying signature on every published artifact with absent and invalid signatures distinguished, sources and documentation archives or reasoned exemptions everywhere, documentation generated under warnings-as-errors, a refused snapshot version, source-control coordinates derived rather than restated, and every failure reported together.
