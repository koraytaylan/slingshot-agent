---
id: interop-coverage-gate
title: "Interop Coverage Gate"
workstream: "0004"
kind: task
depends_on:
  - public-sling-tier
  - quality-gate-entry
gated: false
touches:
  - "interop/scenarios/**"
  - scripts/quality
  - policy/quality-gate.toml
  - development/src/main/java/rs/slingshot/agent/development/ScenarioInventory.java
  - development/src/test/java/rs/slingshot/agent/development/ScenarioInventoryTest.java
  - "development/src/test/resources/fixtures/scenario-inventory/**"
status: done
merged_as: ""
---
# Interop Coverage Gate

"Every feature brings its own interop test" is either a rule a check enforces or a sentence in a contributing guide that stops being true in about a month. This makes it the first one, starting from a commit where the comparison is vacuous, so that it is already in place when the registry has rows in it.

**Steps:**

1. Author fixtures for an accepted inventory, for a scenario naming a feature that does not exist, for a feature with no scenario, for a scenario naming a tier that does not exist, and for a duplicate scenario.
2. Give each scenario its own file under `interop/scenarios/`, naming its identifier, the feature it covers, the tier it needs, the deployment rows it applies to, and the class that runs it. One file per scenario rather than one shared list, so a plan adding sixty scenarios does not serialise sixty tasks behind one file.
3. Implement the inventory check to compare the declared scenarios against the classes that exist in both directions, so a scenario with no class and a class with no row both fail.
4. Implement the feature comparison against whichever inventory names features — in this commit the route table, from Plan 0005 onward the command registry — read from the committed file rather than named here a second time.
5. Add the check to `scripts/quality` and make its failure name the uncovered feature and the tier a scenario for it would need.

**Tests:**

- The accepted inventory passes, and an unknown feature, an uncovered feature, an unknown tier, a duplicate row, and a scenario class with no row are each rejected distinctly.
- A scenario declaring a deployment row absent from the matrix is rejected naming both.
- The comparison is proved to read the feature inventory rather than a copy, by a fixture that adds a feature and expects the failure without any change to this check.
- The gate's failure message is asserted to name the uncovered feature and the tier, checked against a fixture rather than by matching prose.

- **Done when:** `./mvnw verify -pl development -Dtest=ScenarioInventoryTest && scripts/quality` proves two-way scenario-to-class correspondence, five distinct rejections, refusal of a scenario on an undeclared deployment row, and a coverage comparison that reads the feature inventory so that adding a feature fails until it brings a scenario.
