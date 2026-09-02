---
id: project-scaffold
title: "Project Scaffold From Adobe's Archetype"
workstream: "0001"
kind: task
depends_on: []
gated: false
touches:
  - pom.xml
  - mvnw
  - mvnw.cmd
  - .mvn/wrapper/maven-wrapper.properties
  - core/pom.xml
  - aem/pom.xml
  - ui.apps/pom.xml
  - ui.apps.structure/pom.xml
  - ui.config/pom.xml
  - all/pom.xml
  - development/pom.xml
  - interop/pom.xml
  - support/scaffold-provenance.toml
  - core/src/main/java/rs/slingshot/agent/package-info.java
  - aem/src/main/java/rs/slingshot/agent/aem/package-info.java
  - interop/src/main/java/rs/slingshot/agent/interop/package-info.java
  - development/src/test/java/rs/slingshot/agent/development/ProjectScaffoldTest.java
  - "development/src/test/resources/fixtures/project-scaffold/**"
status: done
merged_as: ""
---
# Project Scaffold From Adobe's Archetype

The skeleton has to be one somebody else can regenerate. An archetype run nobody recorded is a project whose shape came from a version that has since moved, and the first question anyone asks about an Adobe Experience Manager project is which archetype produced it.

**Steps:**

1. Author the structure fixture before the manifests: the exact eight modules, their artifact identifiers, their packaging types, the aggregator's inherited properties, and the generated modules that must be absent.
2. Record the generation in `support/scaffold-provenance.toml`: the archetype group, artifact, and exact version, every property passed to it, and the modules that were removed afterwards with a reason each. This file is provenance, not configuration; nothing reads it at build time.
3. Commit the Maven wrapper pinned to one exact Maven version, and record that version in the provenance file, so the reactor is built by a version somebody chose rather than whichever one is on the machine.
4. Create the aggregator with the eight modules, one version property, and the dependency and plugin management every module inherits. No module declares a version for anything the aggregator manages. The group identifier is `rs.slingshot` and the Java package root is `rs.slingshot.agent`, both stated once and inherited. Both reverse `slingshot.rs`, a domain the owner holds, so the namespace is one they can prove rather than one they assumed.
5. Create `core` and `aem` as bundle modules with `bnd-maven-plugin`, `ui.apps`, `ui.apps.structure`, and `ui.config` as content packages, `all` as the container, and `development` and `interop` as ordinary jars that produce no installable artifact. Declare each module's complete product dependency set here — the single provided platform artifact, and `aem`'s provided edge to `core` — so no later task edits a module manifest to reach an interface, and configure the bundle plugins to take exported packages from package annotations rather than from a manifest instruction.
6. Remove the generated front-end, mutable-content, dispatcher, and user-interface test modules, and leave no reference to them in the aggregator, in `all`, or in any filter.
7. Give each Java module one `package-info.java` and nothing else, so that every module compiles and produces its artifact with no product code in it.

**Tests:**

- The structure assertion reads the effective model and compares the module set with the exact eight, rejecting a missing module, an extra one, or a changed packaging type.
- The four modules the archetype generates and this product does not have are asserted absent from the aggregator, from the container package's embeds, and from every filter.
- Every module's version and group identifier are asserted to come from the aggregator, and a fixture with either declared on a module is rejected.
- Every Java source is asserted to sit under the `rs.slingshot.agent` package root, and a fixture outside it is rejected.
- The group identifier is asserted to be the package root's own prefix, so the coordinate and the source tree cannot drift apart.
- `support/scaffold-provenance.toml` parses, names an exact archetype version and an exact Maven wrapper version rather than a range, and lists a reason for each removed module; a fixture with a version range is rejected.
- The whole reactor builds and each module produces exactly the artifact type its row declares.
- No bundle manifest declares an exported package, and a fixture declaring one there rather than by annotation is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=ProjectScaffoldTest && ./mvnw verify` proves the exact eight-module reactor, the absence of the four pruned modules, aggregator-owned versioning and group identifier, one `rs.slingshot.agent` package root, and recorded archetype provenance, with every module producing its declared artifact and no product code in any of them.
