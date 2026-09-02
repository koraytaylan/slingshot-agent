---
id: present-state-documentation
title: "Present-State Documentation"
workstream: "0040"
kind: task
depends_on:
  - release-acceptance-matrix
gated: false
touches:
  - README.md
  - ARCHITECTURE.md
  - CONTRIBUTING.md
  - docs/INSTALLING.md
  - docs/SECURITY.md
  - policy/documentation-rules.toml
  - docs/DOCUMENTATION_REVIEW.md
  - development/src/test/java/rs/slingshot/agent/development/PresentStateTest.java
status: done
merged_as: ""
---
# Present-State Documentation

The last thing, and the one that decides whether anybody can use any of it. Documentation describes the repository in the commit it ships with, not a plan for it, and the difference matters most at the moment there is finally something worth describing.

**Steps:**

1. Rewrite the root documents against what the repository now contains: the routes, the sixty-four commands, the console, the tiers, the deployment rows with their actual evidence, and the licence.
2. Write `docs/INSTALLING.md` for the operator who is about to put this on an author: how it arrives on each deployment row — embedded in their own project's container and deployed through their pipeline where `/apps` is immutable, installed as a file where it is not — what to configure, which platform prerequisites it depends on, and how to tell it is working, using the health checks rather than prose. Say what it costs their instance in the terms a platform team asks in: the repository writes one operation makes, the concurrency bound that caps them, the request threads a stream does not hold, and the fact that nothing here adds an index.
3. Write `docs/SECURITY.md` stating the security model plainly, in the order somebody evaluating it would ask: which groups may call and that the shipped configuration names `administrators` alone; that a command executes inside its caller's own request on their own session, so it does exactly what that person could have done by hand and nothing more; that the agent holds no power over anybody's identity — no impersonation, no stored credential, no token; what the service user's own grants are, which do not include writing content; what the platform controls can and cannot reach; and what this agent deliberately does not do. State the trust boundary in one sentence somebody can quote, and do not soften it: widening the permitted groups widens who can act through the agent, and that is the decision an operator is making.
4. Assert every claim a document makes about a route, a command, a tier, a deployment row, or a health check against the committed source of that fact, in both directions.
5. Separate what a checker decides from what it cannot: assert the falsifiable forms and record accuracy, completeness, and whether a failure message tells a reader what to do as a closed review checklist with the completed review beside it.

**Tests:**

- Every route, command, tier, deployment row, and health check named in any document exists in its committed source, and every one of those exists in a document, in both directions.
- No product document carries an unfinished-work marker or a planning heading, proved against a fixture carrying each.
- Every deployment row's documented status equals the acceptance matrix's, and a document claiming more is rejected.
- `docs/SECURITY.md` names every grant in the repository-access policy and every rule the authorization table declares, in both directions.
- `docs/INSTALLING.md` names an arrival path for every deployment row and every platform prerequisite the routes depend on, in both directions against their committed sources, and a row with no stated arrival path is rejected.
- The review checklist entries are exactly what the checker does not decide, and a fixture checker restating one is rejected.

- **Done when:** `./mvnw verify -pl development -Dtest=PresentStateTest && scripts/quality` proves two-way correspondence between every documented route, command, tier, row, and health check and its committed source, no unfinished-work marker or planning heading, documented row status equal to the acceptance matrix, complete two-way security-model coverage, and a review checklist the checker does not pretend to decide.
