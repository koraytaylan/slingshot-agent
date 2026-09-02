---
id: imported-package-footprint
title: "Imported Package Footprint"
workstream: "0001"
kind: task
depends_on:
  - module-dependency-direction
  - supported-deployment-matrix
gated: false
touches:
  - policy/imported-packages.toml
  - development/src/main/java/rs/slingshot/agent/development/ImportedPackages.java
  - development/src/test/java/rs/slingshot/agent/development/ImportedPackagesTest.java
  - "development/src/test/resources/fixtures/imported-packages/**"
status: done
merged_as: ""
---
# Imported Package Footprint

A bundle's compatibility is exactly the set of packages it imports and the ranges it accepts. Leaving that set to whatever the build tool inferred means nobody chose it, and nobody will notice the day it grows.

**Steps:**

1. Author fixtures for the accepted footprint, for a manifest importing a package with no row, for a row nothing imports, for a widened range, and for a manifest carrying an embedding instruction.
2. Write `policy/imported-packages.toml` with one row per imported package: the package name, the accepted version range, the bundle that imports it, and the deployment rows that provide it.
3. Read the built bundles' manifests rather than the build configuration, and compare the complete import set against the policy in both directions.
4. Refuse any `Private-Package`, `Embed-Dependency`, `Include-Resource` naming a jar, or `Bundle-ClassPath` entry other than the bundle itself, so nothing arrives inside the artifact.
5. Refuse a row whose providing deployment rows do not cover every row in `support/deployments.toml`, so an import that only some supported deployment offers is a decision somebody made in writing.

**Tests:**

- The accepted footprint passes and the check reports the exact import set it read from the manifest.
- An unlisted import, an unused row, and a range wider than the policy declares are each rejected distinctly.
- A manifest carrying an embedding or private-package instruction is rejected, and so is one whose class path names a second entry.
- A row not provided by every deployment row is rejected and the message names the deployment that lacks it.
- The `core` bundle's import set is asserted to contain no Adobe-namespaced package, from the manifest rather than the classpath.

- **Done when:** `./mvnw verify -pl development -Dtest=ImportedPackagesTest` proves exact two-way correspondence between built manifests and the policy, rejects an unlisted import, an unused row, a widened range, every embedding instruction, and an import no supported deployment universally provides, and proves `core` imports nothing Adobe-namespaced.
