---
id: module-dependency-direction
title: "Module Dependency Direction"
workstream: "0001"
kind: task
depends_on:
  - repository-policy-toolkit
gated: false
touches:
  - policy/module-direction.toml
  - development/src/main/java/rs/slingshot/agent/development/ModuleDirection.java
  - development/src/test/java/rs/slingshot/agent/development/ModuleDirectionTest.java
  - "development/src/test/resources/fixtures/module-direction/**"
status: done
merged_as: ""
---
# Module Dependency Direction

The two-bundle split is the whole reason a public interop tier is possible. It survives exactly as long as somebody is checking that `core` still compiles without Adobe on the path, which is a check and not a habit.

**Steps:**

1. Author fixtures for the accepted edge set, for an edge from `core` to `aem`, for a product module reaching `interop`, and for a bundle depending on a content package.
2. Write `policy/module-direction.toml` naming every module and the modules it may depend on, with the scope each edge may use — including the tooling modules' test-scope edges to the product modules, which are permitted in that direction and at that scope alone.
3. Implement the direction check over the resolved reactor model rather than over the declared text, so an edge inherited from management is seen.
4. Refuse an edge `core` to `aem`, any product edge to `development` or `interop`, and any bundle edge to a content-package module.
5. Assert that `core` declares no dependency supplying a `com.day.cq` or `com.adobe.granite` package, and that `aem` declares `core` at provided scope rather than embedding it.

**Tests:**

- The accepted reactor passes, and each forbidden edge — `core` to `aem`, product to tooling, between the two tooling modules, and bundle to content package — is rejected naming both modules and the rule.
- A tooling edge to a product module is accepted at test scope and rejected at compile scope, proving the direction is permitted and the scope is not.
- An edge that exists only through dependency management is caught, proved by a fixture whose module declares no dependency and inherits one.
- A module missing from the policy file and a policy row naming a module that does not exist are both rejected.
- `core`'s resolved compile classpath is asserted to contain no artifact carrying an Adobe-namespaced package.

- **Done when:** `./mvnw verify -pl development -Dtest=ModuleDirectionTest` proves the accepted edge set, rejects every forbidden edge including one inherited from management, accepts a tooling-to-product edge at test scope while rejecting it at compile scope, rejects an unlisted module in either direction, and proves `core` resolves with no Adobe-namespaced package on its classpath.
