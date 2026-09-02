---
id: content-package-analysis-gate
title: "Content Package Analysis Gate"
workstream: "0003"
kind: task
depends_on:
  - supported-deployment-matrix
  - coverage-floor
gated: false
touches:
  - pom.xml
  - all/pom.xml
  - ui.apps/pom.xml
  - ui.apps.structure/pom.xml
  - ui.config/pom.xml
  - policy/package-analysis.toml
  - development/src/main/java/rs/slingshot/agent/development/PackageAnalysis.java
  - development/src/test/java/rs/slingshot/agent/development/PackageAnalysisTest.java
  - "development/src/test/resources/fixtures/package-analysis/**"
status: done
merged_as: ""
---
# Content Package Analysis Gate

Adobe runs an analysis over a content package before it will deploy one, and it is the same analysis a project can run locally. Discovering a package-level defect from a deployment pipeline is discovering it in the most expensive place there is.

**Steps:**

1. Author fixtures for an accepted container, a package with an unresolvable bundle requirement, a package writing outside its declared roots, and a package with an overlapping filter.
2. Bind Adobe's container analyser to the `all` module for every deployment row in the matrix, and fail the build on any finding rather than reporting one.
3. Bind the FileVault package validators to every content-package module with the filter, path, access-control, and dependency validators enabled, and fail on the first finding.
4. Write `policy/package-analysis.toml` naming the enabled analyser tasks and validators, and refuse a disabled one or a lowered severity.
5. Assert the container embeds exactly the five packages and bundles it should, in the declared install order, and that no package writes outside the roots `ui.apps.structure` declares.

**Tests:**

- The accepted container passes every analyser task and validator, and the check reports the exact task set it ran.
- An unresolvable bundle requirement, a write outside the declared roots, and an overlapping filter are each rejected distinctly.
- A disabled analyser task or validator and a lowered severity are both rejected naming the task.
- The container's embed set and install order are asserted exactly, and a fixture with a reordered embed is rejected.
- The analysis runs for every row in the deployment matrix, proved by a fixture row whose finding differs from another row's.

- **Done when:** `./mvnw verify -pl development -Dtest=PackageAnalysisTest` proves the container passes the full analyser task and validator set for every deployment row, that unresolvable requirements, out-of-root writes, and overlapping filters each fail, that no task can be disabled or softened, and that the embed set and install order are exact.
