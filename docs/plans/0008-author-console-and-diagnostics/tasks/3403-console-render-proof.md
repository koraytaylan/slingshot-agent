---
id: console-render-proof
title: "Console Render Proof"
workstream: "0034"
kind: task
depends_on:
  - console-security-proof
gated: false
touches:
  - interop/src/test/java/rs/slingshot/agent/interop/ConsoleRenderScenario.java
  - interop/scenarios/console-render.toml
  - docs/CONSOLE.md
  - policy/documentation-rules.toml
status: done
merged_as: ""
---
# Console Render Proof

Granite renders server-side, so the console's markup is fully determined by a server response. A browser driver would add a large dependency and a class of flakiness to prove something already decided, so this proof reads the markup directly.

**Steps:**

1. Drive a complete workload against a running author: submit several commands through the route, let one fail, let one produce an artifact, and let one still be running.
2. Request every console page as an authorized viewer and assert the rendered markup contains the expected rows and values for that workload, matched against the stores rather than against literals written here.
3. Drive the live tail the way the client library does — subscribing to the event route with the same parameters — and assert it would receive exactly the ledger's events in order.
4. Assert every rendered value that came from a store equals what the corresponding route or data source reports, so the console and the machine-readable surface cannot disagree.
5. Write `docs/CONSOLE.md` describing what each page shows, what it deliberately does not offer, and who may see it, with the pages named from the built package rather than listed by hand.

**Tests:**

- Every console page renders the expected rows for the driven workload, matched against the stores.
- A failed operation's detail page shows its failure category, and a running one shows a non-terminal state with a live lease.
- The tail subscription receives exactly the ledger's events in order, driven the way the client library drives it.
- Every store-derived rendered value equals what the corresponding route reports, compared field by field.
- Every page named in `docs/CONSOLE.md` exists in the built package and every page in the package is named, in both directions.

- **Done when:** `./mvnw verify -pl interop -Dtest=ConsoleRenderScenario && scripts/quality` proves every page rendering the driven workload's rows matched against the stores, a failure category and a live non-terminal state shown, a tail receiving exactly the ledger in order, field-by-field agreement between rendered values and route answers, and two-way correspondence between the documented pages and the built package.
