---
id: route-aliases-and-reconciliation
title: "Route Aliases and Cross-Repository Reconciliation"
workstream: "0016"
kind: task
depends_on:
  - artifact-transfer-route
gated: false
touches:
  - policy/agent-routes.toml
  - policy/client-route-constants.toml
  - policy/design-patterns.toml
  - core/src/main/java/rs/slingshot/agent/route/RouteAlias.java
  - core/src/main/java/rs/slingshot/agent/route/AgentRouteTable.java
  - core/src/main/java/rs/slingshot/agent/http/RouteAliasSwitch.java
  - core/src/main/java/rs/slingshot/agent/http/RequestShape.java
  - core/src/test/java/rs/slingshot/agent/http/RouteAliasTest.java
  - ui.config/src/main/content/jcr_root/apps/slingshot-agent/osgiconfig/config/rs.slingshot.agent.http.RouteAliasSwitch.cfg.json
  - docs/DEPLOYMENT.md
  - docs/CLIENT_COMPATIBILITY.md
  - CONTRIBUTING.md
  - development/src/main/java/rs/slingshot/agent/development/RouteAliasCoverage.java
  - development/src/main/java/rs/slingshot/agent/development/ScenarioInventory.java
  - development/src/test/java/rs/slingshot/agent/development/RouteAliasCoverageTest.java
  - "development/src/test/resources/fixtures/route-alias-coverage/**"
  - interop/src/test/java/rs/slingshot/agent/interop/tier/RouteAliasScenario.java
  - interop/scenarios/route-alias.toml
status: done
merged_as: ""
---
# Route Aliases and Cross-Repository Reconciliation

Three spellings across two repositories, and no single one of them served by everything that expects it. The client's production constants say `/libs/slingshot/agent/…`; its own simulator and daemon suites say `/bin/slingshot/agent/…`; and the two disagree again about `operations` against `snapshot` and `artifacts` against `artifact`.

Serving the aliases is what lets the two halves be proved against each other today. Writing down the correction is what stops "we still serve `/libs`" from becoming a thing nobody remembers deciding.

They are off unless a deployment turns them on. `/libs` is a namespace customers treat as static — their dispatcher and their content delivery network are frequently configured to pass it more freely than anything else, because that is where client libraries live — and an authenticated state-changing route sitting in it is a wider surface than the one this agent asked for. A deployment running a client that needs the old spellings enables them deliberately and reads why; a deployment whose client has caught up never has them at all.

**Steps:**

1. Record every route constant the client repository actually declares in `policy/client-route-constants.toml`, with the file and the symbol each came from, so the list is evidence rather than recollection.
2. Declare each needed alias in the route table with the canonical route it aliases, the client version it exists for, and the correction it is waiting on, and gate the whole alias set behind an Open Service Gateway Initiative configuration that is off in the shipped configuration, so an alias is served because somebody chose it rather than because it was declared.
3. Implement `RouteAlias` so an alias is a second path to one servlet and never a second implementation, and prove an alias and its canonical route answer byte-identically.
4. Implement the coverage check comparing the alias set against the recorded client constants in both directions: a client constant nothing serves fails, and an alias nothing needs fails.
5. Write `docs/CLIENT_COMPATIBILITY.md` stating which spelling is canonical, why `/libs` is wrong as a destination, what the client repository has to change, and what this repository will remove when it does; and record in `docs/DEPLOYMENT.md` the exact dispatcher rules a deployment needs for the prefix to be reachable at all.

**Tests:**

- Every recorded client constant is served, and every alias is asserted to correspond to a recorded constant.
- An alias and its canonical route are asserted byte-identical across every route, response and refusal alike.
- An alias with no recorded client constant fails, and a recorded constant with no alias or canonical route fails.
- Each alias declares a client version and a pending correction, and one declaring neither is rejected.
- With aliases enabled on a running instance, every recorded client constant is reachable and answers identically to its canonical route.
- With the shipped configuration unchanged, every alias is unreachable and only the canonical routes answer, so a deployment that never enabled them carries none of the surface.

- **Done when:** `./mvnw verify -pl development -Dtest=RouteAliasCoverageTest && ./mvnw verify -pl interop -Dtest=RouteAliasScenario` proves two-way correspondence between aliases and the client's recorded constants, byte-identical answers from alias and canonical routes including refusals, a declared version and pending correction on every alias, aliases unreachable under the shipped configuration, and every recorded constant reachable once a deployment enables them.
