---
id: maintenance-console
title: "Maintenance Console"
workstream: "0032"
kind: task
depends_on:
  - live-event-tail
gated: false
touches:
  - aem/src/main/java/rs/slingshot/agent/aem/console/MaintenanceDataSource.java
  - "ui.apps/src/main/content/jcr_root/apps/slingshot-agent/content/console/maintenance/**"
  - aem/src/test/java/rs/slingshot/agent/aem/console/MaintenanceDataSourceTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/MaintenanceConsoleScenario.java
  - interop/scenarios/maintenance-console.toml
status: done
merged_as: ""
---
# Maintenance Console

Where an operator finds out that a store is filling up, which is a question nobody thinks to ask until it matters and by then the answer is expensive.

**Steps:**

1. Author fixtures for a store well within capacity, one near a bound, one at a bound, a store with retained generations, and one whose last sweep was interrupted.
2. Implement `MaintenanceDataSource` reporting the current generation, every retained generation with its retention instant, each capacity counter against its bound, and the last sweep's report.
3. Show a counter approaching its bound as approaching rather than as a number somebody has to compare themselves, using the bound from the contract rather than a threshold written here.
4. Show the last sweep's cursor position and whether it completed, since an interrupted sweep that keeps being interrupted is a store that will never drain.
5. Offer no action. Rotating a generation and running a sweep are state changes with their own guards, and a console button that performed one would be a second submission path with no derived idempotency key.

**Tests:**

- Every capacity counter is shown against its contract bound, and a fixture threshold written in this module is rejected by the source policy.
- A counter at its bound is shown as at the bound and one below as approaching, both proved at exact values.
- Retained generations are listed with their retention instants, and an expired one is shown as expired rather than omitted.
- An interrupted sweep is shown as incomplete with its cursor position.
- The page is asserted to offer no control that performs a state change, over the rendered markup.

- **Done when:** `./mvnw verify -pl aem -Dtest=MaintenanceDataSourceTest && ./mvnw verify -pl interop -Dtest=MaintenanceConsoleScenario` proves counters shown against contract bounds with no threshold declared locally, exact at-bound and approaching states, retained generations listed with expiry shown rather than omitted, an incomplete sweep shown with its cursor, and no state-changing control in the rendered markup.
