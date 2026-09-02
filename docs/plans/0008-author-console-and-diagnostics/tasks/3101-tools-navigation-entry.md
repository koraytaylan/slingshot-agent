---
id: tools-navigation-entry
title: "Tools Navigation Entry"
workstream: "0031"
kind: task
depends_on: []
gated: false
touches:
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/nav/**"
  - ui.apps/src/main/content/jcr_root/apps/cq/core/content/nav/tools/slingshot-agent/.content.xml
  - ui.apps/src/main/content/META-INF/vault/filter.xml
  - development/src/main/java/rs/slingshot/agent/development/OverlayAudit.java
  - development/src/test/java/rs/slingshot/agent/development/OverlayAuditTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/NavigationScenario.java
  - interop/scenarios/navigation.toml
status: done
merged_as: ""
---
# Tools Navigation Entry

An operator who has just installed this looks under Tools, the way they look for anything else that was installed into their author. If nothing is there they conclude nothing was installed — and they are not wrong to.

Using Adobe's extension point correctly means adding rather than replacing. An overlay that shadows an Adobe resource is the defect that appears at the next upgrade rather than at install, which is the worst possible time to find it.

**Steps:**

1. Author the overlay fixtures first: a package shadowing an Adobe resource, a filter reaching outside the declared roots, and an accepted arrangement.
2. Create this product's own navigation content under its declared root, and one tools navigation entry pointing at it, at the path Adobe's extension point reads.
3. Extend the package filter to cover exactly the navigation leaf the structure package already declares, with a rule narrow enough that it can remove only the node it created — the root was declared in Plan 0001 precisely so this task adds content inside a declared root rather than widening one.
4. Implement `OverlayAudit` comparing every path the built packages write against the resources the platform provides, so a path that would shadow an Adobe resource fails the build naming both.
5. Give the entry an icon and a title from the translation dictionary rather than a literal, so the entry is translatable from the first commit rather than retrofitted.

**Tests:**

- Every path the built packages write is asserted not to shadow a platform-provided resource; a fixture package that shadows one fails naming the path.
- The filter is asserted to cover exactly the structure package's two declared roots and no more, and a wider rule — in particular one reaching the navigation parent — is rejected.
- Uninstalling the package is proved to remove only what it created, by comparing the tools navigation before and after on a running instance.
- The entry's title comes from the dictionary, and a fixture using a literal is rejected.
- On a running instance, the entry appears under Tools for a permitted-group member and the target page resolves.

- **Done when:** `./mvnw verify -pl development -Dtest=OverlayAuditTest && ./mvnw verify -pl interop -Dtest=NavigationScenario` proves no built path shadows a platform resource, a filter covering exactly the declared root and one navigation node, an uninstall that removes only what was created, a dictionary-sourced title, and the entry appearing under Tools with its target resolving on a running instance.
