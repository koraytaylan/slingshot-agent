---
id: client-conformance-tier
title: "Client Conformance Tier"
workstream: "0016"
kind: task
depends_on:
  - response-redaction-suite
gated: false
touches:
  - support/client-tier.toml
  - scripts/interop_client_tier
  - interop/src/main/java/rs/slingshot/agent/interop/tier/ClientTier.java
  - interop/src/main/java/rs/slingshot/agent/interop/harness/ProcessRun.java
  - interop/src/test/java/rs/slingshot/agent/interop/tier/ClientConformanceScenario.java
  - interop/scenarios/client-conformance.toml
  - policy/design-patterns.toml
  - docs/INTEROP.md
status: done
merged_as: ""
---
# Client Conformance Tier

The only thing in either repository that proves the two halves speak to one another. Its failures are cross-repository defects rather than local ones, which is exactly why it names the client commit it ran against and claims nothing about any other.

**Steps:**

1. Author fixtures for an absent client executable, a client whose recorded commit does not match, and an accepted arrangement.
2. Write `support/client-tier.toml` pinning the client by origin, exact commit, and the digest of the executable an owner supplies, with an acknowledgement field only an owner sets.
3. Implement `ClientTier` to run the pinned executable against the quickstart tier's instance, configured through the client's own profile mechanism and nothing else, so the exchange is the one a user would have.
4. Run the conformance scenario: discovery, a submission, a stream followed to a terminal state, a lookup, an artifact fetch, and a resend proved to converge — each asserted from the client's own output rather than from this side's store.
5. Report the client commit in the result, and refuse to claim anything about any other commit or any other client build.

**Tests:**

- An absent executable, a commit mismatch, and a missing acknowledgement each refuse distinctly without starting anything.
- Discovery through the client succeeds and its reported capabilities equal this agent's document.
- A submission followed to a terminal state through the client's own stream handling produces the outcome this agent's store holds.
- A resend after an interrupted submission converges on one operation, asserted from the client's output and from this agent's store independently.
- The reported result names the exact client commit, and a fixture claiming a range or a version is rejected.

- **Done when:** `scripts/interop_client_tier` refuses distinctly on an absent executable, a commit mismatch, and a missing acknowledgement, and with an owner-supplied pinned client proves discovery, submission, streamed terminal outcome, lookup, artifact fetch, and convergent resend from both sides independently, reporting the exact client commit and claiming nothing beyond it.
