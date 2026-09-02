---
id: owner-supplied-quickstart-tier
title: "Owner-Supplied Quickstart Tier"
workstream: "0004"
kind: task
depends_on:
  - public-sling-tier
gated: false
touches:
  - scripts/interop_quickstart_tier
  - support/quickstart-tier.toml
  - interop/src/main/java/rs/slingshot/agent/interop/tier/QuickstartTier.java
  - "interop/src/main/resources/tier/quickstart/**"
  - interop/src/test/java/rs/slingshot/agent/interop/tier/QuickstartTierTest.java
  - .gitignore
status: done
merged_as: ""
---
# Owner-Supplied Quickstart Tier

Adobe's quickstart is licensed to whoever holds it. It is never committed, never cached here, never published, and never fetched. Its absence refuses this tier explicitly, because a suite that quietly does not run is a suite reporting a success it did not earn.

**Steps:**

1. Author fixtures for an absent jar, a jar whose digest does not match the owner's record, a jar present with no licence acknowledgement, and an accepted arrangement.
2. Write `support/quickstart-tier.toml` recording the owner-supplied jar's expected digest, the Adobe Experience Manager version it is, the deployment row it stands for, and an acknowledgement field only an owner sets.
3. Build the tier image locally from the owner's jar at run time, never as a build artifact, and label it so the harness's leak check and the engine's own list can find it.
4. Implement `QuickstartTier` to install both bundles and all three content packages, wait for readiness, and expose the same client interface `PublicSlingTier` does, so a scenario is written once and run on either.
5. Write `scripts/interop_quickstart_tier` as the separate command that runs this tier, keep it out of `scripts/quality`, and make an absent jar, a digest mismatch, and a missing acknowledgement three distinct refusals that name what to do.

**Tests:**

- An absent jar, a mismatched digest, and a missing acknowledgement each refuse distinctly, and none starts a container.
- The built image is asserted never to be pushed and never to be written into the repository, by a working-tree comparison across a full run.
- The repository ignore rules are asserted to cover every path the tier writes, and a fixture jar placed in the tree is asserted untracked.
- Both bundles reach the active state with every import resolved, asserted from the running instance.
- The same `WalkingSkeletonScenario` passes unchanged on this tier, proving one scenario runs on either.

- **Done when:** `scripts/interop_quickstart_tier` refuses distinctly on an absent jar, a mismatched digest, and a missing acknowledgement without starting anything, and with an owner-supplied jar present installs both bundles active with every import resolved, runs the unchanged walking-skeleton scenario, publishes no image, and leaves the working tree byte-identical.
