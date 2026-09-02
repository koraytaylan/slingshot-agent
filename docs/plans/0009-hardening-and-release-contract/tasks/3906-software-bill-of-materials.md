---
id: software-bill-of-materials
title: "Software Bill of Materials"
workstream: "0039"
kind: task
depends_on:
  - owner-supplied-release-metadata
gated: false
touches:
  - all/pom.xml
  - support/release-artifacts.toml
  - development/src/main/java/rs/slingshot/agent/development/BillOfMaterials.java
  - development/src/test/java/rs/slingshot/agent/development/BillOfMaterialsTest.java
status: done
merged_as: ""
---
# Software Bill of Materials

For a product that embeds nothing, the bill of materials is the shortest interesting document it produces — and that shortness is exactly the claim worth publishing in a machine-readable form somebody can check without reading a README.

**Steps:**

1. Author fixtures for a bill listing an artifact the archives do not contain, one omitting an artifact they do, and one whose declared relationships disagree with the imported-package footprint.
2. Produce a bill of materials for the release, covering both bundles and the container package, in a standard machine-readable form.
3. Assert the bill's contained-artifact set is exactly what the archives contain, in both directions, so it is generated from the artifacts rather than maintained beside them.
4. Assert the declared external relationships are exactly the provided dependencies from `policy/dependencies.toml`, so what the agent binds to at run time is stated even though it embeds none of it.
5. Include the bill in the release artifact inventory with its own digest, so it is verified like every other artifact.

**Tests:**

- The bill's contained set equals the archives' contents in both directions; a fixture with an extra or missing entry fails naming it.
- The declared external relationships equal the provided dependency set, and a disagreement with the imported-package footprint fails naming the package.
- The bill is in the release artifact inventory with a digest that matches its bytes.
- Two builds produce byte-identical bills, so a difference is a real change.
- The bill records that the bundles embed no third-party code, and a fixture bundle that embeds some makes that record fail.

- **Done when:** `./mvnw verify -pl development -Dtest=BillOfMaterialsTest` proves a bill whose contained set equals the archives in both directions, external relationships equal to the provided dependency set and consistent with the imported-package footprint, inclusion in the release inventory with a matching digest, byte-identical bills across two builds, and a recorded no-embedded-third-party claim that fails when it stops being true.
