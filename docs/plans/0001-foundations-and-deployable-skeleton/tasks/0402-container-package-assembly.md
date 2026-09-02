---
id: container-package-assembly
title: "Container Package Assembly"
workstream: "0004"
kind: task
depends_on:
  - route-table-and-capability-servlet
  - content-package-analysis-gate
gated: false
touches:
  - all/pom.xml
  - ui.apps/pom.xml
  - ui.apps/src/main/content/META-INF/vault/filter.xml
  - ui.apps/src/main/content/jcr_root/apps/slingshot-agent/.content.xml
  - ui.apps.structure/pom.xml
  - development/src/test/java/rs/slingshot/agent/development/ContainerPackageTest.java
  - "development/src/test/resources/fixtures/container-package/**"
status: done
merged_as: ""
---
# Container Package Assembly

One artifact, whether a customer's own build embeds it or an operator installs it by hand. What that artifact contains, where each part goes, and in what order the parts install is the difference between a package that deploys anywhere and one that works on the machine it was built on — and it has to be the same artifact either way, because a Cloud Service deployment embeds it in somebody else's container and their pipeline is where a defect in it would surface.

**Steps:**

1. Author fixtures for the accepted container contents, for a bundle installed at the wrong run mode, for a filter overlapping another package's roots, and for a package with no declared dependency on the structure package.
2. Declare the immutable roots this product writes in `ui.apps.structure` — `/apps/slingshot-agent`, and the single tools navigation node at `/apps/cq/core/content/nav/tools/slingshot-agent` that Plan 0008 fills — and make every other content package declare a dependency on it. Two roots rather than one because the navigation entry has to sit where the platform's own extension point reads, and declaring the exact leaf rather than its parent is what keeps this package from colliding with any other product that also adds an entry there.
3. Assemble `all` embedding both bundles and the three content packages, each at its declared install path and run mode, with the author run mode the only one either bundle is installed under.
4. Write `ui.apps`'s filter to cover exactly `/apps/slingshot-agent` with no additional root and no rule that would remove anything outside it.
5. Assert the built container's contents rather than its configuration: the exact embedded artifact set, each one's path inside the package, the install order, and the run mode.

**Tests:**

- The built container's entry set is asserted exactly; a missing embed, an extra embed, and a changed install path are each rejected.
- Both bundles are asserted installed under the author run mode only, and a fixture installing one unconditionally is rejected.
- The filter is asserted to cover exactly the structure package's declared roots and no more, and an overlapping or wider filter is rejected — including one declaring the navigation parent rather than this product's own leaf.
- A content package with no dependency on the structure package is rejected naming it.
- Installing the container twice over itself is asserted to leave the same content, by comparing the declared filter's coverage against the entry set.

- **Done when:** `./mvnw verify -pl development -Dtest=ContainerPackageTest` proves the exact embedded artifact set, install paths, install order, and author-only run mode from the built container, a filter equal to the declared structure root, and a rejected package that omits the structure dependency.
