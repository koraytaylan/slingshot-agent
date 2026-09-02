---
id: dependency-policy-and-locked-cache
title: "Dependency Policy and Locked Cache"
workstream: "0003"
kind: task
depends_on:
  - imported-package-footprint
  - content-package-analysis-gate
gated: false
touches:
  - pom.xml
  - policy/dependencies.toml
  - scripts/prepare_locked_dependency_cache
  - scripts/verify_locked_dependency_cache
  - support/locked-dependency-cache.toml
  - development/src/main/java/rs/slingshot/agent/development/DependencyPolicy.java
  - development/src/test/java/rs/slingshot/agent/development/DependencyPolicyTest.java
  - "development/src/test/resources/fixtures/dependency-policy/**"
status: done
merged_as: ""
---
# Dependency Policy and Locked Cache

A gate that reaches the network is a gate whose result depends on what a remote server felt like serving. Preparing the inputs once, deliberately, and verifying them offline afterwards is the sibling's arrangement, and it is the only way `scripts/quality` can honestly say it fetches nothing.

**Steps:**

1. Author fixtures for the accepted dependency set, for a compile-scope product dependency, for a version range, for a snapshot version, and for a cache missing one artifact.
2. Write `policy/dependencies.toml` with one row per artifact the build resolves: coordinates at an exact version, the scope it may use, the modules that may declare it, and a reason for each test-scope or build-time entry.
3. Refuse a compile-scope or runtime-scope dependency in any product module, refuse a version range, refuse a snapshot, and refuse an artifact with no row or a row nothing uses.
4. Write `scripts/prepare_locked_dependency_cache` as the one command here that reaches the network and says so when it runs, resolving exactly the declared set into a repository-local cache and recording each artifact's digest in `support/locked-dependency-cache.toml`.
5. Write `scripts/verify_locked_dependency_cache` to authenticate that cache offline against the recorded digests without fetching, repairing, or consulting an ambient repository, and to state precisely what verification establishes: that the cache is the one prepared for this declared set, unchanged — and not that its bytes were trustworthy when they were fetched.
6. State the rule the whole arrangement rests on and check it: nothing `scripts/quality` runs reaches the network, and every executable in `scripts/` that does reach it is a preparation or verification command that says so when it runs and that the gate never invokes. The claim here is about dependency resolution — this is the only command that resolves one from a remote repository — and the container images an interop tier needs are prepared and verified the same way by their own commands.

**Tests:**

- The accepted set passes, and a compile-scope product dependency, a range, a snapshot, an unlisted artifact, and an unused row are each rejected distinctly.
- A test-scope or build-time row with no reason is rejected.
- Verification fails on a cache with a missing artifact, an extra artifact, and an artifact whose bytes changed, each named.
- Verification is proved to fetch nothing, by running it with no reachable repository and with an ambient repository containing a different version of one artifact.
- This is proved to be the only executable that resolves a dependency from a remote repository, by a check over every script in `scripts/`.
- No executable `scripts/quality` invokes reaches the network, checked over the gate's declared stages rather than over the whole directory, so a preparation command added later is not mistaken for a gate stage.

- **Done when:** `./mvnw verify -pl development -Dtest=DependencyPolicyTest && scripts/verify_locked_dependency_cache` proves exact two-way correspondence with the declared set, refusal of compile scope, ranges, snapshots, and unreasoned rows, three named cache-authentication failures, offline verification that ignores an ambient repository, and that no executable the gate invokes reaches the network.
