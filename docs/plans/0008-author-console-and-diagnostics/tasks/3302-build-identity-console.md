---
id: build-identity-console
title: "Build Identity Console"
workstream: "0033"
kind: task
depends_on:
  - health-checks
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/BuildIdentityDataSource.java
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console/identity/**"
  - aem/src/test/java/rs/slingshot/agent/aem/console/BuildIdentityDataSourceTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/BuildIdentityScenario.java
  - interop/scenarios/build-identity.toml
status: done
merged_as: ""
---
# Build Identity Console

An operator diagnosing a version disagreement needs to read both sides. This is one of them, rendered for a person: the contract digests, the routes with their aliases, and the commands with their access classes and bounds.

**Steps:**

1. Author fixtures for a build with commands, with none, with aliases, and with an alias whose client version is recorded.
2. Implement `BuildIdentityDataSource` rendering exactly what discovery returns — the transport and canonical contract digests, the event-store generation, the authority readiness, and the registered commands — from the same source discovery reads.
3. Render the route table beside it, with each alias, the canonical route it aliases, the client version it exists for, and the correction it is waiting on.
4. Render each command's wire name, semantic contract version, access class, operation-key requirement, and result bound, in the registry's own order.
5. Prove the page and the discovery route agree by construction: both read one source, and a fixture page reading a second is rejected.

**Tests:**

- The rendered digests, generation, and readiness equal what the discovery route returns, compared field by field on a running instance.
- Every alias is rendered with its canonical route, client version, and pending correction; an alias missing any of those fails.
- Commands are rendered in the registry's own order with every declared field.
- A fixture page reading a second source rather than discovery's is rejected.
- The redaction audit finds nothing on this page, which is the page most likely to render an internal value.

- **Done when:** `./mvnw verify -pl aem -Dtest=BuildIdentityDataSourceTest && ./mvnw verify -pl interop -Dtest=BuildIdentityScenario` proves field-by-field agreement with the discovery route on a running instance, every alias rendered with its canonical route, client version, and pending correction, registry-ordered commands with every declared field, a single shared source, and a clean redaction audit.
