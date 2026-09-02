---
id: credential-exposure-suite
title: "Credential Exposure Suite"
workstream: "0037"
kind: task
depends_on:
  - injection-and-traversal-suite
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/CredentialExposureScenario.java
  - interop/scenarios/credential-exposure.toml
  - development/src/main/java/rs/slingshot/agent/development/ExposureSurface.java
  - development/src/test/java/rs/slingshot/agent/development/ExposureSurfaceTest.java
status: done
merged_as: ""
---
# Credential Exposure Suite

Plan 0004 audited the routes and Plan 0008 audited the console. This audits everything at once, including the places a value leaves that are not responses at all: a log line, a health check message, an event payload, a stored artifact, and a repository property the agent itself wrote.

**Steps:**

1. Plant a distinctive value of every corpus kind everywhere the agent could hold one: the key ring, the configuration, request headers, command arguments, job properties, replication transport addresses, workflow metadata, and repository content.
2. Drive the complete surface — every route, every alias, every console resource, every health check, every command, and every declared failure of each.
3. Scan every response body, every response header, every log line, every emitted event, every stored artifact, and every repository property the agent wrote, for every planted value.
4. Implement `ExposureSurface` deriving the driven surface from the built packages and the registry, so nothing added later escapes the audit by not being listed.
5. Assert the agent's own key ring never leaves the repository: it is not in a response, a log, an event, an artifact, a health check, or the console, under any request.

**Tests:**

- No planted value appears in any response body, header, log line, event, artifact, or agent-written property, across the whole derived surface.
- Each corpus kind has at least one planted value in at least one holder, and a kind with none fails.
- The key ring is proved absent from every one of those places under every request, including under every failure.
- A fixture that leaks one planted value in each of the six places is detected in all six.
- The surface is derived rather than listed, proved by adding a fixture route and expecting it driven with no change to the suite.

- **Done when:** `./mvnw verify -pl interop -Dtest=CredentialExposureScenario && ./mvnw verify -pl development -Dtest=ExposureSurfaceTest` proves no planted value in any body, header, log, event, artifact, or agent-written property across a derived surface, every corpus kind planted, the key ring absent everywhere under every request including failures, and detection of a deliberate leak in each of the six places.
