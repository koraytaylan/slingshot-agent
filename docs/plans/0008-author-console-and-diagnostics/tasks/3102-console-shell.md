---
id: console-shell
title: "Console Shell"
workstream: "0031"
kind: task
depends_on:
  - tools-navigation-entry
gated: false
touches:
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console/**"
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/clientlibs/console/**"
  - development/src/main/java/rs/slingshot/agent/development/FrontEndFootprint.java
  - development/src/test/java/rs/slingshot/agent/development/FrontEndFootprintTest.java
status: done
merged_as: ""
---
# Console Shell

A front-end pipeline is a second dependency graph with its own licences, its own advisories, and its own upgrade cadence. Adding several hundred packages to render four tables would undo the compatibility argument this whole repository rests on.

**Steps:**

1. Author fixtures for an accepted client library, one carrying a generated asset, one declaring a dependency outside the platform's own categories, and a page using a component that is not the platform's.
2. Build the shell from Granite's own page and shell components, with every console page a resource whose type is a platform component rather than a custom one.
3. Create one client library with hand-written source only, declaring a category of this product's own and depending only on the platform's own categories.
4. Implement `FrontEndFootprint` asserting the built packages contain no minified bundle, no source map, no package manifest, no lock file, and no vendored third-party asset.
5. Refuse a client library that declares an external dependency, so the console cannot acquire a toolchain by accident later.

**Tests:**

- The built packages contain no generated or vendored client-side asset, proved over the produced archives against a fixture that carries each.
- Every console page's resource type is a platform-provided component, and a fixture using a custom one is rejected.
- The client library declares only platform categories, and a fixture declaring an external dependency is rejected.
- The client library's source is asserted hand-written by the absence of any build step producing it, checked over the reactor.
- The shell renders a complete page for an authorized viewer, asserted from the server-rendered markup.

- **Done when:** `./mvnw verify -pl development -Dtest=FrontEndFootprintTest` proves no generated, minified, vendored, or manifest asset in any built package, every console page typed by a platform component, a client library with only platform dependencies and no build step, and a complete server-rendered shell.
