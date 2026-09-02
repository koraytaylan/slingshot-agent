---
id: platform-version-floor
title: "Platform Version Floor"
workstream: "0038"
kind: task
depends_on:
  - storage-upgrade-compatibility
gated: false
touches:
  - support/deployments.toml
  - development/src/main/java/rs/slingshot/agent/development/PlatformFloor.java
  - development/src/test/java/rs/slingshot/agent/development/PlatformFloorTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/PlatformFloorScenario.java
  - interop/scenarios/platform-floor.toml
status: done
merged_as: ""
---
# Platform Version Floor

The minimum platform version is bound to the deployment rows rather than written separately, so raising it is a change to the matrix — and the matrix is already what the bytecode contract and the imported-package footprint check against.

**Steps:**

1. Author fixtures for a row below the floor, a row at it, an imported package range no row satisfies, and a row whose platform version and Java runtime disagree with each other.
2. Add the minimum platform version to each deployment row, and derive the floor as the lowest across the rows rather than declaring it a second time.
3. Implement `PlatformFloor` checking that every imported package range is satisfied by every row at or above the floor, and that the bytecode target is satisfied by every row's Java runtime.
4. Refuse installation on a platform below the floor at run time as well as at build time, with a message naming the version found and the version required.
5. Assert the floor cannot be lowered without a row to justify it, so the number is always the consequence of the matrix rather than an independent claim.

**Tests:**

- The floor equals the lowest row's minimum version, and a fixture declaring it separately is rejected.
- An imported package range no row at or above the floor satisfies fails naming both.
- A row whose Java runtime is below the bytecode target fails, and a row whose platform version implies a different runtime fails distinctly.
- On a running instance below the floor, installation is refused at run time naming both versions.
- Lowering the floor without a row justifying it is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=PlatformFloorTest && ./mvnw verify -pl interop -Dtest=PlatformFloorScenario` proves a floor derived from the matrix rather than declared, every imported range satisfied by every row at or above it, distinct Java-runtime and platform-version disagreements, a run-time refusal below the floor naming both versions, and refusal to lower the floor without a justifying row.
