---
id: public-sling-tier
title: "Public Apache Sling Interop Tier"
workstream: "0004"
kind: task
depends_on:
  - podman-process-harness
  - service-user-and-repository-initialisation
gated: false
touches:
  - interop/scenarios/walking-skeleton.toml
  - interop/src/main/java/rs/slingshot/agent/interop/tier/PublicSlingTier.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/InteropTier.java
  - interop/src/main/java/rs/slingshot/agent/interop/tier/package-info.java
  - "interop/src/main/resources/tier/sling/**"
  - support/interop-images.toml
  - scripts/prepare_interop_images
  - scripts/verify_interop_images
  - interop/src/test/java/rs/slingshot/agent/interop/tier/PublicSlingTierTest.java
  - interop/src/test/java/rs/slingshot/agent/interop/WalkingSkeletonScenario.java
status: done
merged_as: ""
---
# Public Apache Sling Interop Tier

The tier that needs nothing licensed is the one that will actually run on every change, so it is the one worth making complete. Everything in `core` resolves against a plain Apache Sling runtime, which is the whole reason the bundle split exists.

**Steps:**

1. Author the tier fixtures first: the accepted image pin, a floating tag, an image with no recorded digest, and a scenario declaring a tier that does not exist.
2. Write `support/interop-images.toml` pinning the public Sling image by registry, repository, exact tag, and content digest, and refuse a floating tag or a missing digest. Write `scripts/prepare_interop_images` as the one command that pulls them, saying so when it runs, and `scripts/verify_interop_images` to confirm offline that every pinned image is already present with its exact digest — the same preparation-then-verification arrangement the dependency cache uses, and for the same reason: the gate that runs this tier claims to fetch nothing, and a tier that pulled an image at gate time would make that claim false.
3. Implement `PublicSlingTier` against the `InteropTier` interface: start the already-present image — refusing rather than pulling when it is absent or its digest differs, and naming the preparation command in the refusal — install the built container package, wait for both bundles' readiness, and expose an authenticated client bound to one user.
4. Install only what this tier can resolve — the `core` bundle and the three content packages — and assert the `aem` bundle is absent rather than installed and unresolved, so a failure here is never mistaken for a missing Adobe API.
5. Write `WalkingSkeletonScenario` as the first scenario under `interop/scenarios/`: install, reach `capabilities` as an authenticated user, assert the document field by field, assert the same route refuses an unauthenticated request, and assert the tier leaves no container behind.

**Tests:**

- The image pin parses and a floating tag or absent digest is rejected; the started container's digest is asserted equal to the pinned one.
- An absent image and one whose local digest differs are two distinct refusals that name the preparation command, and neither pulls anything, proved with no reachable network.
- Verification is proved to fetch nothing and to ignore an ambient image of the same repository at a different digest.
- Installation reaches a state where the `core` bundle is active and every one of its imports is resolved, asserted from the running instance rather than from the build.
- The `aem` bundle is asserted absent from this tier, and a fixture installing it is rejected before the container starts.
- The capability document from the running instance is asserted equal to the one the unit suite proved, field by field, so the tier confirms the servlet rather than restating it.
- An unauthenticated request to the same route is refused, and no capability field appears in the refusal body.

- **Done when:** `scripts/verify_interop_images && ./mvnw verify -pl interop -Dtest='PublicSlingTierTest+WalkingSkeletonScenario'` proves two distinct refusals that pull nothing when a pinned image is absent or differs, then starts the digest-pinned public image rootlessly, installs the container package, proves `core` active with every import resolved and `aem` absent, reads a capability document equal to the unit suite's, refuses an unauthenticated request without disclosure, and leaves no container behind.
