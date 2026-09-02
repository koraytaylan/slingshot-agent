---
id: project-documentation
title: "Project Documentation"
workstream: "0004"
kind: task
depends_on:
  - interop-coverage-gate
  - owner-supplied-quickstart-tier
gated: false
touches:
  - README.md
  - ARCHITECTURE.md
  - CONTRIBUTING.md
  - AGENTS.md
  - docs/INTEROP.md
  - policy/documentation-rules.toml
  - docs/DOCUMENTATION_REVIEW.md
  - development/src/main/java/rs/slingshot/agent/development/ProductDocumentation.java
  - development/src/test/java/rs/slingshot/agent/development/ProductDocumentationTest.java
status: done
merged_as: ""
---
# Project Documentation

The root documents describe the repository as it is when this plan lands: two bundles, one route, three tiers, and no Adobe Experience Manager behaviour at all. Every aspiration stays in its plan bundle, which is the rule that keeps a reader from mistaking a plan for a feature.

**Steps:**

1. Write `README.md` saying what installs, what the one route answers, what the three tiers each prove, and — as plainly as the sibling says it — that no command exists yet.
2. Write `ARCHITECTURE.md` describing the module set, the two-bundle split and why the public tier depends on it, the contract file, the route table, and the access model with its two session paths.
3. Write `CONTRIBUTING.md` as the rules a change is held to, each one naming the stage of `scripts/quality` that enforces it, and `AGENTS.md` pointing at it as the first thing to read. Give the code doctrine its own section stating each of the six policies as a sentence somebody can hold in their head — nothing is null, no type is named `Impl`, the ceiling is on nesting, documentation covers what a checker can decide, streams everywhere but the declared hot paths, and the Adobe practices that decide durability — each naming the policy file that decides it.
4. Write `docs/INTEROP.md` describing the three tiers, what each proves, what each refuses, and the exact command for each, with the licensed input's handling stated outright.
5. Split what a checker can decide from what it cannot: assert that no product document carries an unfinished-work marker or a planning heading and that every route and tier named in prose exists in its committed table, and record the rest — accuracy, completeness, whether a failure message tells a reader what to do — as a closed review checklist in `policy/documentation-rules.toml` with the completed review in `docs/DOCUMENTATION_REVIEW.md`.

**Tests:**

- Every route named in a product document exists in the route table and every tier named exists in the tier inventory, in both directions.
- No product document carries an unfinished-work marker or a planning heading, proved against a fixture carrying each.
- Every rule `CONTRIBUTING.md` states names a stage the quality-gate inventory declares, and a fixture rule naming no stage is rejected.
- Every code-doctrine policy file is described in `CONTRIBUTING.md` and every doctrine section names an existing policy file, in both directions, so a policy cannot exist undocumented and a documented one cannot be absent.
- The review checklist entries are asserted to be exactly the questions the checker does not answer, and a fixture checker restating one as a rule is rejected.
- `docs/INTEROP.md`'s commands are asserted to be exactly the executables in `scripts/` that run a tier.

- **Done when:** `./mvnw verify -pl development -Dtest=ProductDocumentationTest && scripts/quality` proves two-way correspondence between documented routes and tiers and their committed tables, absence of unfinished-work markers and planning headings, every contributing rule bound to a gate stage, exact tier commands, and a review checklist the checker does not pretend to decide.
