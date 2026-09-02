---
id: console-security-proof
title: "Console Security Proof"
workstream: "0034"
kind: task
depends_on:
  - accessibility-and-language
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/ConsoleSecurityScenario.java
  - interop/scenarios/console-security.toml
  - development/src/main/java/rs/slingshot/agent/development/ConsoleSurface.java
  - development/src/test/java/rs/slingshot/agent/development/ConsoleSurfaceTest.java
status: done
merged_as: ""
---
# Console Security Proof

The console is where this repository's security model meets a person's browser session for the first time. A data source is a servlet like any other, and the point of this proof is establishing that it is treated like one.

**Steps:**

1. Enumerate the console's whole reachable surface from the built package rather than from a list — every page resource and every data source — so a resource added later is covered without editing this proof.
2. For each, drive it as a permitted-group member, as an authenticated non-member, and as an anonymous request, and assert the second and third are refused identically to a route.
3. Assert the console discloses nothing the routes would not: drive the redaction corpus through every page and data source, including every empty and error state.
4. Assert no console resource performs a state change: every one is driven with every method it will accept, and the store and the platform are asserted unchanged afterwards.
5. Assert no console resource is reachable through a selector, an extension, a suffix, or a trailing path that would bypass its authorization, using the same alternative-spelling corpus Plan 0004 built.

**Tests:**

- Every reachable console resource is covered, derived from the built package; a resource with no coverage fails naming it.
- Non-member and anonymous requests are refused byte-identically to a route, across the whole surface.
- No corpus value appears in any page, data source, empty state, or error state.
- Every resource driven with every accepted method leaves the store and the platform unchanged.
- No alternative path spelling reaches a console resource without its authorization, across the whole surface.

- **Done when:** `./mvnw verify -pl interop -Dtest=ConsoleSecurityScenario && ./mvnw verify -pl development -Dtest=ConsoleSurfaceTest` proves package-derived coverage of every console resource, byte-identical route-equivalent refusals for non-members and anonymous callers, no corpus disclosure in any state, no state change under any accepted method, and no authorization bypass through any alternative path spelling.
